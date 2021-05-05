/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.AliasIdentifier
import org.apache.spark.sql.catalyst.analysis.{AnsiTypeCoercion, MultiInstanceRelation, TypeCoercion, TypeCoercionBase}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.catalyst.catalog.CatalogTable.VIEW_STORING_ANALYZED_PLAN
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, RangePartitioning, RoundRobinPartitioning, SinglePartition}
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.catalyst.trees.TreePattern._
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.util.random.RandomSampler

/**
 * When planning take() or collect() operations, this special node is inserted at the top of
 * the logical plan before invoking the query planner.
 *
 * Rules can pattern-match on this node in order to apply transformations that only take effect
 * at the top of the logical query plan.
 */
case class ReturnAnswer(child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output
  override protected def withNewChildInternal(newChild: LogicalPlan): ReturnAnswer =
    copy(child = newChild)
}

/**
 * This node is inserted at the top of a subquery when it is optimized. This makes sure we can
 * recognize a subquery as such, and it allows us to write subquery aware transformations.
 *
 * @param correlated flag that indicates the subquery is correlated, and will be rewritten into a
 *                   join during analysis.
 */
case class Subquery(child: LogicalPlan, correlated: Boolean) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
  override protected def withNewChildInternal(newChild: LogicalPlan): Subquery =
    copy(child = newChild)
}

object Subquery {
  def fromExpression(s: SubqueryExpression): Subquery =
    Subquery(s.plan, SubqueryExpression.hasCorrelatedSubquery(s))
}

case class Project(projectList: Seq[NamedExpression], child: LogicalPlan)
    extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = projectList.map(_.toAttribute)
  override def maxRows: Option[Long] = child.maxRows

  final override val nodePatterns: Seq[TreePattern] = Seq(PROJECT)

  override lazy val resolved: Boolean = {
    val hasSpecialExpressions = projectList.exists ( _.collect {
        case agg: AggregateExpression => agg
        case generator: Generator => generator
        case window: WindowExpression => window
      }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasSpecialExpressions
  }

  override lazy val validConstraints: ExpressionSet =
    getAllValidConstraints(projectList)

  override def metadataOutput: Seq[Attribute] =
    getTagValue(Project.hiddenOutputTag).getOrElse(Nil)

  override protected def withNewChildInternal(newChild: LogicalPlan): Project =
    copy(child = newChild)
}

object Project {
  val hiddenOutputTag: TreeNodeTag[Seq[Attribute]] = TreeNodeTag[Seq[Attribute]]("hidden_output")
}

/**
 * Applies a [[Generator]] to a stream of input rows, combining the
 * output of each into a new stream of rows.  This operation is similar to a `flatMap` in functional
 * programming with one important additional feature, which allows the input rows to be joined with
 * their output.
 *
 * @param generator the generator expression
 * @param unrequiredChildIndex this parameter starts as Nil and gets filled by the Optimizer.
 *                             It's used as an optimization for omitting data generation that will
 *                             be discarded next by a projection.
 *                             A common use case is when we explode(array(..)) and are interested
 *                             only in the exploded data and not in the original array. before this
 *                             optimization the array got duplicated for each of its elements,
 *                             causing O(n^^2) memory consumption. (see [SPARK-21657])
 * @param outer when true, each input row will be output at least once, even if the output of the
 *              given `generator` is empty.
 * @param qualifier Qualifier for the attributes of generator(UDTF)
 * @param generatorOutput The output schema of the Generator.
 * @param child Children logical plan node
 */
case class Generate(
    generator: Generator,
    unrequiredChildIndex: Seq[Int],
    outer: Boolean,
    qualifier: Option[String],
    generatorOutput: Seq[Attribute],
    child: LogicalPlan)
  extends UnaryNode {

  final override val nodePatterns: Seq[TreePattern] = Seq(GENERATE)

  lazy val requiredChildOutput: Seq[Attribute] = {
    val unrequiredSet = unrequiredChildIndex.toSet
    child.output.zipWithIndex.filterNot(t => unrequiredSet.contains(t._2)).map(_._1)
  }

  override lazy val resolved: Boolean = {
    generator.resolved &&
      childrenResolved &&
      generator.elementSchema.length == generatorOutput.length &&
      generatorOutput.forall(_.resolved)
  }

  override def producedAttributes: AttributeSet = AttributeSet(generatorOutput)

  def qualifiedGeneratorOutput: Seq[Attribute] = {
    val qualifiedOutput = qualifier.map { q =>
      // prepend the new qualifier to the existed one
      generatorOutput.map(a => a.withQualifier(Seq(q)))
    }.getOrElse(generatorOutput)
    val nullableOutput = qualifiedOutput.map {
      // if outer, make all attributes nullable, otherwise keep existing nullability
      a => a.withNullability(outer || a.nullable)
    }
    nullableOutput
  }

  def output: Seq[Attribute] = requiredChildOutput ++ qualifiedGeneratorOutput

  override protected def withNewChildInternal(newChild: LogicalPlan): Generate =
    copy(child = newChild)
}

case class Filter(condition: Expression, child: LogicalPlan)
  extends OrderPreservingUnaryNode with PredicateHelper {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = child.maxRows

  final override val nodePatterns: Seq[TreePattern] = Seq(FILTER)

  override protected lazy val validConstraints: ExpressionSet = {
    val predicates = splitConjunctivePredicates(condition)
      .filterNot(SubqueryExpression.hasCorrelatedSubquery)
    child.constraints.union(ExpressionSet(predicates))
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): Filter =
    copy(child = newChild)
}

abstract class SetOperation(left: LogicalPlan, right: LogicalPlan) extends BinaryNode {

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  protected def leftConstraints: ExpressionSet = left.constraints

  protected def rightConstraints: ExpressionSet = {
    require(left.output.size == right.output.size)
    val attributeRewrites = AttributeMap(right.output.zip(left.output))
    right.constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  override lazy val resolved: Boolean =
    childrenResolved &&
      left.output.length == right.output.length &&
      left.output.zip(right.output).forall { case (l, r) =>
        l.dataType.sameType(r.dataType)
      } && duplicateResolved
}

object SetOperation {
  def unapply(p: SetOperation): Option[(LogicalPlan, LogicalPlan)] = Some((p.left, p.right))
}

case class Intersect(
    left: LogicalPlan,
    right: LogicalPlan,
    isAll: Boolean) extends SetOperation(left, right) {

  override def nodeName: String = getClass.getSimpleName + ( if ( isAll ) "All" else "" )

  final override val nodePatterns: Seq[TreePattern] = Seq(INTERSECT)

  override def output: Seq[Attribute] =
    left.output.zip(right.output).map { case (leftAttr, rightAttr) =>
      leftAttr.withNullability(leftAttr.nullable && rightAttr.nullable)
    }

  override def metadataOutput: Seq[Attribute] = Nil

  override protected lazy val validConstraints: ExpressionSet =
    leftConstraints.union(rightConstraints)

  override def maxRows: Option[Long] = {
    if (children.exists(_.maxRows.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRows).min)
    }
  }

  override protected def withNewChildrenInternal(
    newLeft: LogicalPlan, newRight: LogicalPlan): Intersect = copy(left = newLeft, right = newRight)
}

case class Except(
    left: LogicalPlan,
    right: LogicalPlan,
    isAll: Boolean) extends SetOperation(left, right) {
  override def nodeName: String = getClass.getSimpleName + ( if ( isAll ) "All" else "" )
  /** We don't use right.output because those rows get excluded from the set. */
  override def output: Seq[Attribute] = left.output

  override def metadataOutput: Seq[Attribute] = Nil

  final override val nodePatterns : Seq[TreePattern] = Seq(EXCEPT)

  override protected lazy val validConstraints: ExpressionSet = leftConstraints

  override protected def withNewChildrenInternal(
    newLeft: LogicalPlan, newRight: LogicalPlan): Except = copy(left = newLeft, right = newRight)
}

/** Factory for constructing new `Union` nodes. */
object Union {
  def apply(left: LogicalPlan, right: LogicalPlan): Union = {
    Union (left :: right :: Nil)
  }
}

/**
 * Logical plan for unioning multiple plans, without a distinct. This is UNION ALL in SQL.
 *
 * @param byName          Whether resolves columns in the children by column names.
 * @param allowMissingCol Allows missing columns in children query plans. If it is true,
 *                        this function allows different set of column names between two Datasets.
 *                        This can be set to true only if `byName` is true.
 */
case class Union(
    children: Seq[LogicalPlan],
    byName: Boolean = false,
    allowMissingCol: Boolean = false) extends LogicalPlan {
  assert(!allowMissingCol || byName, "`allowMissingCol` can be true only if `byName` is true.")

  override def maxRows: Option[Long] = {
    if (children.exists(_.maxRows.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRows).sum)
    }
  }

  final override val nodePatterns: Seq[TreePattern] = Seq(UNION)

  /**
   * Note the definition has assumption about how union is implemented physically.
   */
  override def maxRowsPerPartition: Option[Long] = {
    if (children.exists(_.maxRowsPerPartition.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRowsPerPartition).sum)
    }
  }

  def duplicateResolved: Boolean = {
    children.map(_.outputSet.size).sum ==
      AttributeSet.fromAttributeSets(children.map(_.outputSet)).size
  }

  // updating nullability to make all the children consistent
  override def output: Seq[Attribute] = {
    children.map(_.output).transpose.map { attrs =>
      val firstAttr = attrs.head
      val nullable = attrs.exists(_.nullable)
      val newDt = attrs.map(_.dataType).reduce(StructType.merge)
      if (firstAttr.dataType == newDt) {
        firstAttr.withNullability(nullable)
      } else {
        AttributeReference(firstAttr.name, newDt, nullable, firstAttr.metadata)(
          firstAttr.exprId, firstAttr.qualifier)
      }
    }
  }

  override def metadataOutput: Seq[Attribute] = Nil

  override lazy val resolved: Boolean = {
    // allChildrenCompatible needs to be evaluated after childrenResolved
    def allChildrenCompatible: Boolean =
      children.tail.forall( child =>
        // compare the attribute number with the first child
        child.output.length == children.head.output.length &&
        // compare the data types with the first child
        child.output.zip(children.head.output).forall {
          case (l, r) => l.dataType.sameType(r.dataType)
        })
    children.length > 1 && !(byName || allowMissingCol) && childrenResolved && allChildrenCompatible
  }

  /**
   * Maps the constraints containing a given (original) sequence of attributes to those with a
   * given (reference) sequence of attributes. Given the nature of union, we expect that the
   * mapping between the original and reference sequences are symmetric.
   */
  private def rewriteConstraints(
      reference: Seq[Attribute],
      original: Seq[Attribute],
      constraints: ExpressionSet): ExpressionSet = {
    require(reference.size == original.size)
    val attributeRewrites = AttributeMap(original.zip(reference))
    constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  private def merge(a: ExpressionSet, b: ExpressionSet): ExpressionSet = {
    val common = a.intersect(b)
    // The constraint with only one reference could be easily inferred as predicate
    // Grouping the constraints by it's references so we can combine the constraints with same
    // reference together
    val othera = a.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    val otherb = b.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    // loose the constraints by: A1 && B1 || A2 && B2  ->  (A1 || A2) && (B1 || B2)
    val others = (othera.keySet intersect otherb.keySet).map { attr =>
      Or(othera(attr).reduceLeft(And), otherb(attr).reduceLeft(And))
    }
    common ++ others
  }

  override protected lazy val validConstraints: ExpressionSet = {
    children
      .map(child => rewriteConstraints(children.head.output, child.output, child.constraints))
      .reduce(merge(_, _))
  }

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[LogicalPlan]): Union =
    copy(children = newChildren)
}

case class Join(
    left: LogicalPlan,
    right: LogicalPlan,
    joinType: JoinType,
    condition: Option[Expression],
    hint: JoinHint)
  extends BinaryNode with PredicateHelper {

  override def maxRows: Option[Long] = {
    joinType match {
      case Inner | Cross | FullOuter | LeftOuter | RightOuter
        if left.maxRows.isDefined && right.maxRows.isDefined =>
        val maxRows = BigInt(left.maxRows.get) * BigInt(right.maxRows.get)
        if (maxRows.isValidLong) {
          Some(maxRows.toLong)
        } else {
          None
        }

      case LeftSemi | LeftAnti =>
        left.maxRows

      case _ =>
        None
    }
  }

  override def output: Seq[Attribute] = {
    joinType match {
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case _ =>
        left.output ++ right.output
    }
  }

  override def metadataOutput: Seq[Attribute] = {
    joinType match {
      case ExistenceJoin(_) =>
        left.metadataOutput
      case LeftExistence(_) =>
        left.metadataOutput
      case _ =>
        children.flatMap(_.metadataOutput)
    }
  }

  override protected lazy val validConstraints: ExpressionSet = {
    joinType match {
      case _: InnerLike if condition.isDefined =>
        left.constraints
          .union(right.constraints)
          .union(ExpressionSet(splitConjunctivePredicates(condition.get)))
      case LeftSemi if condition.isDefined =>
        left.constraints
          .union(ExpressionSet(splitConjunctivePredicates(condition.get)))
      case j: ExistenceJoin =>
        left.constraints
      case _: InnerLike =>
        left.constraints.union(right.constraints)
      case LeftExistence(_) =>
        left.constraints
      case LeftOuter =>
        left.constraints
      case RightOuter =>
        right.constraints
      case _ =>
        ExpressionSet()
    }
  }

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  // Joins are only resolved if they don't introduce ambiguous expression ids.
  // NaturalJoin should be ready for resolution only if everything else is resolved here
  lazy val resolvedExceptNatural: Boolean = {
    childrenResolved &&
      expressions.forall(_.resolved) &&
      duplicateResolved &&
      condition.forall(_.dataType == BooleanType)
  }

  // if not a natural join, use `resolvedExceptNatural`. if it is a natural join or
  // using join, we still need to eliminate natural or using before we mark it resolved.
  override lazy val resolved: Boolean = joinType match {
    case NaturalJoin(_) => false
    case UsingJoin(_, _) => false
    case _ => resolvedExceptNatural
  }

  override val nodePatterns : Seq[TreePattern] = {
    var patterns = Seq(JOIN)
    joinType match {
      case _: InnerLike => patterns = patterns :+ INNER_LIKE_JOIN
      case LeftOuter | FullOuter | RightOuter => patterns = patterns :+ OUTER_JOIN
      case LeftSemiOrAnti(_) => patterns = patterns :+ LEFT_SEMI_OR_ANTI_JOIN
      case NaturalJoin(_) | UsingJoin(_, _) => patterns = patterns :+ NATURAL_LIKE_JOIN
      case _ =>
    }
    patterns
  }

  // Ignore hint for canonicalization
  protected override def doCanonicalize(): LogicalPlan =
    super.doCanonicalize().asInstanceOf[Join].copy(hint = JoinHint.NONE)

  // Do not include an empty join hint in string description
  protected override def stringArgs: Iterator[Any] = super.stringArgs.filter { e =>
    (!e.isInstanceOf[JoinHint]
      || e.asInstanceOf[JoinHint].leftHint.isDefined
      || e.asInstanceOf[JoinHint].rightHint.isDefined)
  }

  override protected def withNewChildrenInternal(
    newLeft: LogicalPlan, newRight: LogicalPlan): Join = copy(left = newLeft, right = newRight)
}

/**
 * Insert query result into a directory.
 *
 * @param isLocal Indicates whether the specified directory is local directory
 * @param storage Info about output file, row and what serialization format
 * @param provider Specifies what data source to use; only used for data source file.
 * @param child The query to be executed
 * @param overwrite If true, the existing directory will be overwritten
 *
 * Note that this plan is unresolved and has to be replaced by the concrete implementations
 * during analysis.
 */
case class InsertIntoDir(
    isLocal: Boolean,
    storage: CatalogStorageFormat,
    provider: Option[String],
    child: LogicalPlan,
    overwrite: Boolean = true)
  extends UnaryNode {

  override def output: Seq[Attribute] = Seq.empty
  override def metadataOutput: Seq[Attribute] = Nil
  override lazy val resolved: Boolean = false

  override protected def withNewChildInternal(newChild: LogicalPlan): InsertIntoDir =
    copy(child = newChild)
}

/**
 * A container for holding the view description(CatalogTable) and info whether the view is temporary
 * or not. If it's a SQL (temp) view, the child should be a logical plan parsed from the
 * `CatalogTable.viewText`. Otherwise, the view is a temporary one created from a dataframe and the
 * view description should contain a `VIEW_CREATED_FROM_DATAFRAME` property; in this case, the child
 * must be already resolved.
 *
 * This operator will be removed at the end of analysis stage.
 *
 * @param desc A view description(CatalogTable) that provides necessary information to resolve the
 *             view.
 * @param isTempView A flag to indicate whether the view is temporary or not.
 * @param child The logical plan of a view operator. If the view description is available, it should
 *              be a logical plan parsed from the `CatalogTable.viewText`.
 */
case class View(
    desc: CatalogTable,
    isTempView: Boolean,
    child: LogicalPlan) extends UnaryNode {
  require(!isTempViewStoringAnalyzedPlan || child.resolved)

  override def output: Seq[Attribute] = child.output

  override def metadataOutput: Seq[Attribute] = Nil

  override def simpleString(maxFields: Int): String = {
    s"View (${desc.identifier}, ${output.mkString("[", ",", "]")})"
  }

  override def doCanonicalize(): LogicalPlan = child match {
    case p: Project if p.resolved && canRemoveProject(p) => p.child.canonicalized
    case _ => child.canonicalized
  }

  def isTempViewStoringAnalyzedPlan: Boolean =
    isTempView && desc.properties.contains(VIEW_STORING_ANALYZED_PLAN)

  // When resolving a SQL view, we use an extra Project to add cast and alias to make sure the view
  // output schema doesn't change even if the table referenced by the view is changed after view
  // creation. We should remove this extra Project during canonicalize if it does nothing.
  // See more details in `SessionCatalog.fromCatalogTable`.
  private def canRemoveProject(p: Project): Boolean = {
    p.output.length == p.child.output.length && p.projectList.zip(p.child.output).forall {
      case (Alias(cast: CastBase, name), childAttr) =>
        cast.child match {
          case a: AttributeReference =>
            a.dataType == cast.dataType && a.name == name && childAttr.semanticEquals(a)
          case _ => false
        }
      case _ => false
    }
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): View =
    copy(child = newChild)
}

object View {
  def effectiveSQLConf(configs: Map[String, String], isTempView: Boolean): SQLConf = {
    val activeConf = SQLConf.get
    // For temporary view, we always use captured sql configs
    if (activeConf.useCurrentSQLConfigsForView && !isTempView) return activeConf

    val sqlConf = new SQLConf()
    for ((k, v) <- configs) {
      sqlConf.settings.put(k, v)
    }
    sqlConf
  }
}

/**
 * A container for holding named common table expressions (CTEs) and a query plan.
 * This operator will be removed during analysis and the relations will be substituted into child.
 *
 * @param child The final query of this CTE.
 * @param cteRelations A sequence of pair (alias, the CTE definition) that this CTE defined
 *                     Each CTE can see the base tables and the previously defined CTEs only.
 */
case class With(child: LogicalPlan, cteRelations: Seq[(String, SubqueryAlias)]) extends UnaryNode {
  override def output: Seq[Attribute] = child.output

  override def simpleString(maxFields: Int): String = {
    val cteAliases = truncatedString(cteRelations.map(_._1), "[", ", ", "]", maxFields)
    s"CTE $cteAliases"
  }

  override def innerChildren: Seq[LogicalPlan] = cteRelations.map(_._2)

  override protected def withNewChildInternal(newChild: LogicalPlan): With = copy(child = newChild)
}

case class WithWindowDefinition(
    windowDefinitions: Map[String, WindowSpecDefinition],
    child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
  override protected def withNewChildInternal(newChild: LogicalPlan): WithWindowDefinition =
    copy(child = newChild)
}

/**
 * @param order  The ordering expressions
 * @param global True means global sorting apply for entire data set,
 *               False means sorting only apply within the partition.
 * @param child  Child logical plan
 */
case class Sort(
    order: Seq[SortOrder],
    global: Boolean,
    child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = child.maxRows
  override def outputOrdering: Seq[SortOrder] = order
  final override val nodePatterns: Seq[TreePattern] = Seq(SORT)
  override protected def withNewChildInternal(newChild: LogicalPlan): Sort = copy(child = newChild)
}

/** Factory for constructing new `Range` nodes. */
object Range {
  def apply(start: Long, end: Long, step: Long, numSlices: Int): Range = {
    Range(start, end, step, Some(numSlices))
  }

  def getOutputAttrs: Seq[Attribute] = {
    StructType(StructField("id", LongType, nullable = false) :: Nil).toAttributes
  }

  private def typeCoercion: TypeCoercionBase = {
    if (SQLConf.get.ansiEnabled) AnsiTypeCoercion else TypeCoercion
  }

  private def castAndEval[T](expression: Expression, dataType: DataType): T = {
    typeCoercion.implicitCast(expression, dataType)
      .map(_.eval())
      .filter(_ != null)
      .getOrElse {
        throw QueryCompilationErrors.incompatibleRangeInputDataTypeError(expression, dataType)
      }.asInstanceOf[T]
  }

  def toLong(expression: Expression): Long = castAndEval[Long](expression, LongType)

  def toInt(expression: Expression): Int = castAndEval[Int](expression, IntegerType)
}

@ExpressionDescription(
  usage = """
    _FUNC_(start: long, end: long, step: long, numSlices: integer)
    _FUNC_(start: long, end: long, step: long)
    _FUNC_(start: long, end: long)
    _FUNC_(end: long)""",
  examples = """
    Examples:
      > SELECT * FROM _FUNC_(1);
        +---+
        | id|
        +---+
        |  0|
        +---+
      > SELECT * FROM _FUNC_(0, 2);
        +---+
        |id |
        +---+
        |0  |
        |1  |
        +---+
      > SELECT * FROM _FUNC_(0, 4, 2);
        +---+
        |id |
        +---+
        |0  |
        |2  |
        +---+
  """,
  since = "2.0.0",
  group = "table_funcs")
case class Range(
    start: Long,
    end: Long,
    step: Long,
    numSlices: Option[Int],
    override val output: Seq[Attribute] = Range.getOutputAttrs,
    override val isStreaming: Boolean = false)
  extends LeafNode with MultiInstanceRelation {

  require(step != 0, s"step ($step) cannot be 0")

  def this(start: Expression, end: Expression, step: Expression, numSlices: Expression) =
    this(Range.toLong(start), Range.toLong(end), Range.toLong(step), Some(Range.toInt(numSlices)))

  def this(start: Expression, end: Expression, step: Expression) =
    this(Range.toLong(start), Range.toLong(end), Range.toLong(step), None)

  def this(start: Expression, end: Expression) = this(start, end, Literal.create(1L, LongType))

  def this(end: Expression) = this(Literal.create(0L, LongType), end)

  val numElements: BigInt = {
    val safeStart = BigInt(start)
    val safeEnd = BigInt(end)
    if ((safeEnd - safeStart) % step == 0 || (safeEnd > safeStart) != (step > 0)) {
      (safeEnd - safeStart) / step
    } else {
      // the remainder has the same sign with range, could add 1 more
      (safeEnd - safeStart) / step + 1
    }
  }

  def toSQL(): String = {
    if (numSlices.isDefined) {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step, ${numSlices.get})"
    } else {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step)"
    }
  }

  override def newInstance(): Range = copy(output = output.map(_.newInstance()))

  override def simpleString(maxFields: Int): String = {
    s"Range ($start, $end, step=$step, splits=$numSlices)"
  }

  override def maxRows: Option[Long] = {
    if (numElements.isValidLong) {
      Some(numElements.toLong)
    } else {
      None
    }
  }

  override def computeStats(): Statistics = {
    if (numElements == 0) {
      Statistics(sizeInBytes = 0, rowCount = Some(0))
    } else {
      val (minVal, maxVal) = if (step > 0) {
        (start, start + (numElements - 1) * step)
      } else {
        (start + (numElements - 1) * step, start)
      }
      val colStat = ColumnStat(
        distinctCount = Some(numElements),
        max = Some(maxVal),
        min = Some(minVal),
        nullCount = Some(0),
        avgLen = Some(LongType.defaultSize),
        maxLen = Some(LongType.defaultSize))

      Statistics(
        sizeInBytes = LongType.defaultSize * numElements,
        rowCount = Some(numElements),
        attributeStats = AttributeMap(Seq(output.head -> colStat)))
    }
  }

  override def outputOrdering: Seq[SortOrder] = {
    val order = if (step > 0) {
      Ascending
    } else {
      Descending
    }
    output.map(a => SortOrder(a, order))
  }
}

/**
 * This is a Group by operator with the aggregate functions and projections.
 *
 * @param groupingExpressions expressions for grouping keys
 * @param aggregateExpressions expressions for a project list, which could contain
 *                             [[AggregateExpression]]s.
 *
 * Note: Currently, aggregateExpressions is the project list of this Group by operator. Before
 * separating projection from grouping and aggregate, we should avoid expression-level optimization
 * on aggregateExpressions, which could reference an expression in groupingExpressions.
 * For example, see the rule [[org.apache.spark.sql.catalyst.optimizer.SimplifyExtractValueOps]]
 */
case class Aggregate(
    groupingExpressions: Seq[Expression],
    aggregateExpressions: Seq[NamedExpression],
    child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = {
    val hasWindowExpressions = aggregateExpressions.exists ( _.collect {
        case window: WindowExpression => window
      }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasWindowExpressions
  }

  override def output: Seq[Attribute] = aggregateExpressions.map(_.toAttribute)
  override def metadataOutput: Seq[Attribute] = Nil
  override def maxRows: Option[Long] = {
    if (groupingExpressions.isEmpty) {
      Some(1L)
    } else {
      child.maxRows
    }
  }

  final override val nodePatterns : Seq[TreePattern] = Seq(AGGREGATE)

  override lazy val validConstraints: ExpressionSet = {
    val nonAgg = aggregateExpressions.filter(_.find(_.isInstanceOf[AggregateExpression]).isEmpty)
    getAllValidConstraints(nonAgg)
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): Aggregate =
    copy(child = newChild)
}

case class Window(
    windowExpressions: Seq[NamedExpression],
    partitionSpec: Seq[Expression],
    orderSpec: Seq[SortOrder],
    child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] =
    child.output ++ windowExpressions.map(_.toAttribute)

  override def producedAttributes: AttributeSet = windowOutputSet

  final override val nodePatterns: Seq[TreePattern] = Seq(WINDOW)

  def windowOutputSet: AttributeSet = AttributeSet(windowExpressions.map(_.toAttribute))

  override protected def withNewChildInternal(newChild: LogicalPlan): Window =
    copy(child = newChild)
}

object Expand {
  /**
   * Build bit mask from attributes of selected grouping set. A bit in the bitmask is corresponding
   * to an attribute in group by attributes sequence, the selected attribute has corresponding bit
   * set to 0 and otherwise set to 1. For example, if we have GroupBy attributes (a, b, c, d), the
   * bitmask 5(whose binary form is 0101) represents grouping set (a, c).
   *
   * @param groupingSetAttrs The attributes of selected grouping set
   * @param attrMap Mapping group by attributes to its index in attributes sequence
   * @return The bitmask which represents the selected attributes out of group by attributes.
   */
  private def buildBitmask(
    groupingSetAttrs: Seq[Attribute],
    attrMap: Map[Attribute, Int]): Long = {
    val numAttributes = attrMap.size
    assert(numAttributes <= GroupingID.dataType.defaultSize * 8)
    val mask = if (numAttributes != 64) (1L << numAttributes) - 1 else 0xFFFFFFFFFFFFFFFFL
    // Calculate the attribute masks of selected grouping set. For example, if we have GroupBy
    // attributes (a, b, c, d), grouping set (a, c) will produce the following sequence:
    // (15, 7, 13), whose binary form is (1111, 0111, 1101)
    val masks = (mask +: groupingSetAttrs.map(attrMap).map(index =>
      // 0 means that the column at the given index is a grouping column, 1 means it is not,
      // so we unset the bit in bitmap.
      ~(1L << (numAttributes - 1 - index))
    ))
    // Reduce masks to generate an bitmask for the selected grouping set.
    masks.reduce(_ & _)
  }

  /**
   * Apply the all of the GroupExpressions to every input row, hence we will get
   * multiple output rows for an input row.
   *
   * @param groupingSetsAttrs The attributes of grouping sets
   * @param groupByAliases The aliased original group by expressions
   * @param groupByAttrs The attributes of aliased group by expressions
   * @param gid Attribute of the grouping id
   * @param child Child operator
   */
  def apply(
    groupingSetsAttrs: Seq[Seq[Attribute]],
    groupByAliases: Seq[Alias],
    groupByAttrs: Seq[Attribute],
    gid: Attribute,
    child: LogicalPlan): Expand = {
    val attrMap = groupByAttrs.zipWithIndex.toMap

    val hasDuplicateGroupingSets = groupingSetsAttrs.size !=
      groupingSetsAttrs.map(_.map(_.exprId).toSet).distinct.size

    // Create an array of Projections for the child projection, and replace the projections'
    // expressions which equal GroupBy expressions with Literal(null), if those expressions
    // are not set for this grouping set.
    val projections = groupingSetsAttrs.zipWithIndex.map { case (groupingSetAttrs, i) =>
      val projAttrs = child.output ++ groupByAttrs.map { attr =>
        if (!groupingSetAttrs.contains(attr)) {
          // if the input attribute in the Invalid Grouping Expression set of for this group
          // replace it with constant null
          Literal.create(null, attr.dataType)
        } else {
          attr
        }
      // groupingId is the last output, here we use the bit mask as the concrete value for it.
      } :+ {
        val bitMask = buildBitmask(groupingSetAttrs, attrMap)
        val dataType = GroupingID.dataType
        Literal.create(if (dataType.sameType(IntegerType)) bitMask.toInt else bitMask, dataType)
      }

      if (hasDuplicateGroupingSets) {
        // If `groupingSetsAttrs` has duplicate entries (e.g., GROUPING SETS ((key), (key))),
        // we add one more virtual grouping attribute (`_gen_grouping_pos`) to avoid
        // wrongly grouping rows with the same grouping ID.
        projAttrs :+ Literal.create(i, IntegerType)
      } else {
        projAttrs
      }
    }

    // the `groupByAttrs` has different meaning in `Expand.output`, it could be the original
    // grouping expression or null, so here we create new instance of it.
    val output = if (hasDuplicateGroupingSets) {
      val gpos = AttributeReference("_gen_grouping_pos", IntegerType, false)()
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid :+ gpos
    } else {
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid
    }
    Expand(projections, output, Project(child.output ++ groupByAliases, child))
  }
}

/**
 * Apply a number of projections to every input row, hence we will get multiple output rows for
 * an input row.
 *
 * @param projections to apply
 * @param output of all projections.
 * @param child operator.
 */
case class Expand(
    projections: Seq[Seq[Expression]],
    output: Seq[Attribute],
    child: LogicalPlan) extends UnaryNode {
  @transient
  override lazy val references: AttributeSet =
    AttributeSet(projections.flatten.flatMap(_.references))

  override def metadataOutput: Seq[Attribute] = Nil

  override def producedAttributes: AttributeSet = AttributeSet(output diff child.output)

  // This operator can reuse attributes (for example making them null when doing a roll up) so
  // the constraints of the child may no longer be valid.
  override protected lazy val validConstraints: ExpressionSet = ExpressionSet()

  override protected def withNewChildInternal(newChild: LogicalPlan): Expand =
    copy(child = newChild)
}

/**
 * A constructor for creating a pivot, which will later be converted to a [[Project]]
 * or an [[Aggregate]] during the query analysis.
 *
 * @param groupByExprsOpt A sequence of group by expressions. This field should be None if coming
 *                        from SQL, in which group by expressions are not explicitly specified.
 * @param pivotColumn     The pivot column.
 * @param pivotValues     A sequence of values for the pivot column.
 * @param aggregates      The aggregation expressions, each with or without an alias.
 * @param child           Child operator
 */
case class Pivot(
    groupByExprsOpt: Option[Seq[NamedExpression]],
    pivotColumn: Expression,
    pivotValues: Seq[Expression],
    aggregates: Seq[Expression],
    child: LogicalPlan) extends UnaryNode {
  override lazy val resolved = false // Pivot will be replaced after being resolved.
  override def output: Seq[Attribute] = {
    val pivotAgg = aggregates match {
      case agg :: Nil =>
        pivotValues.map(value => AttributeReference(value.toString, agg.dataType)())
      case _ =>
        pivotValues.flatMap { value =>
          aggregates.map(agg => AttributeReference(value + "_" + agg.sql, agg.dataType)())
        }
    }
    groupByExprsOpt.getOrElse(Seq.empty).map(_.toAttribute) ++ pivotAgg
  }
  override def metadataOutput: Seq[Attribute] = Nil

  override protected def withNewChildInternal(newChild: LogicalPlan): Pivot = copy(child = newChild)
}

/**
 * A constructor for creating a logical limit, which is split into two separate logical nodes:
 * a [[LocalLimit]], which is a partition local limit, followed by a [[GlobalLimit]].
 *
 * This muds the water for clean logical/physical separation, and is done for better limit pushdown.
 * In distributed query processing, a non-terminal global limit is actually an expensive operation
 * because it requires coordination (in Spark this is done using a shuffle).
 *
 * In most cases when we want to push down limit, it is often better to only push some partition
 * local limit. Consider the following:
 *
 *   GlobalLimit(Union(A, B))
 *
 * It is better to do
 *   GlobalLimit(Union(LocalLimit(A), LocalLimit(B)))
 *
 * than
 *   Union(GlobalLimit(A), GlobalLimit(B)).
 *
 * So we introduced LocalLimit and GlobalLimit in the logical plan node for limit pushdown.
 */
object Limit {
  def apply(limitExpr: Expression, child: LogicalPlan): UnaryNode = {
    GlobalLimit(limitExpr, LocalLimit(limitExpr, child))
  }

  def unapply(p: GlobalLimit): Option[(Expression, LogicalPlan)] = {
    p match {
      case GlobalLimit(le1, LocalLimit(le2, child)) if le1 == le2 => Some((le1, child))
      case _ => None
    }
  }
}

/**
 * A global (coordinated) limit. This operator can emit at most `limitExpr` number in total.
 *
 * See [[Limit]] for more information.
 */
case class GlobalLimit(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }

  final override val nodePatterns: Seq[TreePattern] = Seq(LIMIT)

  override protected def withNewChildInternal(newChild: LogicalPlan): GlobalLimit =
    copy(child = newChild)
}

/**
 * A partition-local (non-coordinated) limit. This operator can emit at most `limitExpr` number
 * of tuples on each physical partition.
 *
 * See [[Limit]] for more information.
 */
case class LocalLimit(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output

  override def maxRowsPerPartition: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }

  final override val nodePatterns: Seq[TreePattern] = Seq(LIMIT)

  override protected def withNewChildInternal(newChild: LogicalPlan): LocalLimit =
    copy(child = newChild)
}

/**
 * This is similar with [[Limit]] except:
 *
 * - It does not have plans for global/local separately because currently there is only single
 *   implementation which initially mimics both global/local tails. See
 *   `org.apache.spark.sql.execution.CollectTailExec` and
 *   `org.apache.spark.sql.execution.CollectLimitExec`
 *
 * - Currently, this plan can only be a root node.
 */
case class Tail(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): Tail = copy(child = newChild)
}

/**
 * Aliased subquery.
 *
 * @param identifier the alias identifier for this subquery.
 * @param child the logical plan of this subquery.
 */
case class SubqueryAlias(
    identifier: AliasIdentifier,
    child: LogicalPlan)
  extends OrderPreservingUnaryNode {

  def alias: String = identifier.name

  override def output: Seq[Attribute] = {
    val qualifierList = identifier.qualifier :+ alias
    child.output.map(_.withQualifier(qualifierList))
  }

  override def metadataOutput: Seq[Attribute] = {
    val qualifierList = identifier.qualifier :+ alias
    child.metadataOutput.map(_.withQualifier(qualifierList))
  }

  override def maxRows: Option[Long] = child.maxRows

  override def doCanonicalize(): LogicalPlan = child.canonicalized

  override protected def withNewChildInternal(newChild: LogicalPlan): SubqueryAlias =
    copy(child = newChild)
}

object SubqueryAlias {
  def apply(
      identifier: String,
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier), child)
  }

  def apply(
      identifier: String,
      database: String,
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier, Seq(database)), child)
  }

  def apply(
      multipartIdentifier: Seq[String],
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(multipartIdentifier.last, multipartIdentifier.init), child)
  }
}
/**
 * Sample the dataset.
 *
 * @param lowerBound Lower-bound of the sampling probability (usually 0.0)
 * @param upperBound Upper-bound of the sampling probability. The expected fraction sampled
 *                   will be ub - lb.
 * @param withReplacement Whether to sample with replacement.
 * @param seed the random seed
 * @param child the LogicalPlan
 */
case class Sample(
    lowerBound: Double,
    upperBound: Double,
    withReplacement: Boolean,
    seed: Long,
    child: LogicalPlan) extends UnaryNode {

  val eps = RandomSampler.roundingEpsilon
  val fraction = upperBound - lowerBound
  if (withReplacement) {
    require(
      fraction >= 0.0 - eps,
      s"Sampling fraction ($fraction) must be nonnegative with replacement")
  } else {
    require(
      fraction >= 0.0 - eps && fraction <= 1.0 + eps,
      s"Sampling fraction ($fraction) must be on interval [0, 1] without replacement")
  }

  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output

  override protected def withNewChildInternal(newChild: LogicalPlan): Sample =
    copy(child = newChild)
}

/**
 * Returns a new logical plan that dedups input rows.
 */
case class Distinct(child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output
  final override val nodePatterns: Seq[TreePattern] = Seq(DISTINCT_LIKE)
  override protected def withNewChildInternal(newChild: LogicalPlan): Distinct =
    copy(child = newChild)
}

/**
 * A base interface for [[RepartitionByExpression]] and [[Repartition]]
 */
abstract class RepartitionOperation extends UnaryNode {
  def shuffle: Boolean
  def numPartitions: Int
  override final def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output
  final override val nodePatterns: Seq[TreePattern] = Seq(REPARTITION_OPERATION)
  def partitioning: Partitioning
}

/**
 * Returns a new RDD that has exactly `numPartitions` partitions. Differs from
 * [[RepartitionByExpression]] as this method is called directly by DataFrame's, because the user
 * asked for `coalesce` or `repartition`. [[RepartitionByExpression]] is used when the consumer
 * of the output requires some specific ordering or distribution of the data.
 */
case class Repartition(numPartitions: Int, shuffle: Boolean, child: LogicalPlan)
  extends RepartitionOperation {
  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")

  override def partitioning: Partitioning = {
    require(shuffle, "Partitioning can only be used in shuffle.")
    numPartitions match {
      case 1 => SinglePartition
      case _ => RoundRobinPartitioning(numPartitions)
    }
  }

  override protected def withNewChildInternal(newChild: LogicalPlan): Repartition =
    copy(child = newChild)
}

/**
 * This method repartitions data using [[Expression]]s into `optNumPartitions`, and receives
 * information about the number of partitions during execution. Used when a specific ordering or
 * distribution is expected by the consumer of the query result. Use [[Repartition]] for RDD-like
 * `coalesce` and `repartition`. If no `optNumPartitions` is given, by default it partitions data
 * into `numShufflePartitions` defined in `SQLConf`, and could be coalesced by AQE.
 */
case class RepartitionByExpression(
    partitionExpressions: Seq[Expression],
    child: LogicalPlan,
    optNumPartitions: Option[Int]) extends RepartitionOperation {

  val numPartitions = optNumPartitions.getOrElse(conf.numShufflePartitions)
  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")

  override val partitioning: Partitioning = {
    val (sortOrder, nonSortOrder) = partitionExpressions.partition(_.isInstanceOf[SortOrder])

    require(sortOrder.isEmpty || nonSortOrder.isEmpty,
      s"${getClass.getSimpleName} expects that either all its `partitionExpressions` are of type " +
        "`SortOrder`, which means `RangePartitioning`, or none of them are `SortOrder`, which " +
        "means `HashPartitioning`. In this case we have:" +
      s"""
         |SortOrder: $sortOrder
         |NonSortOrder: $nonSortOrder
       """.stripMargin)

    if (numPartitions == 1) {
      SinglePartition
    } else if (sortOrder.nonEmpty) {
      RangePartitioning(sortOrder.map(_.asInstanceOf[SortOrder]), numPartitions)
    } else if (nonSortOrder.nonEmpty) {
      HashPartitioning(nonSortOrder, numPartitions)
    } else {
      RoundRobinPartitioning(numPartitions)
    }
  }

  override def shuffle: Boolean = true

  override protected def withNewChildInternal(newChild: LogicalPlan): RepartitionByExpression =
    copy(child = newChild)
}

object RepartitionByExpression {
  def apply(
      partitionExpressions: Seq[Expression],
      child: LogicalPlan,
      numPartitions: Int): RepartitionByExpression = {
    RepartitionByExpression(partitionExpressions, child, Some(numPartitions))
  }
}

/**
 * A relation with one row. This is used in "SELECT ..." without a from clause.
 */
case class OneRowRelation() extends LeafNode {
  override def maxRows: Option[Long] = Some(1)
  override def output: Seq[Attribute] = Nil
  override def computeStats(): Statistics = Statistics(sizeInBytes = 1)

  /** [[org.apache.spark.sql.catalyst.trees.TreeNode.makeCopy()]] does not support 0-arg ctor. */
  override def makeCopy(newArgs: Array[AnyRef]): OneRowRelation = {
    val newCopy = OneRowRelation()
    newCopy.copyTagsFrom(this)
    newCopy
  }
}

/** A logical plan for `dropDuplicates`. */
case class Deduplicate(
    keys: Seq[Attribute],
    child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output
  final override val nodePatterns: Seq[TreePattern] = Seq(DISTINCT_LIKE)
  override protected def withNewChildInternal(newChild: LogicalPlan): Deduplicate =
    copy(child = newChild)
}

/**
 * A trait to represent the commands that support subqueries.
 * This is used to allow such commands in the subquery-related checks.
 */
trait SupportsSubquery extends LogicalPlan

/**
 * Collect arbitrary (named) metrics from a dataset. As soon as the query reaches a completion
 * point (batch query completes or streaming query epoch completes) an event is emitted on the
 * driver which can be observed by attaching a listener to the spark session. The metrics are named
 * so we can collect metrics at multiple places in a single dataset.
 *
 * This node behaves like a global aggregate. All the metrics collected must be aggregate functions
 * or be literals.
 */
case class CollectMetrics(
    name: String,
    metrics: Seq[NamedExpression],
    child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = {
    name.nonEmpty && metrics.nonEmpty && metrics.forall(_.resolved) && childrenResolved
  }

  override def output: Seq[Attribute] = child.output

  override protected def withNewChildInternal(newChild: LogicalPlan): CollectMetrics =
    copy(child = newChild)
}

/**
 * A placeholder for domain join that can be added when decorrelating subqueries.
 * It should be rewritten during the optimization phase.
 */
case class DomainJoin(domainAttrs: Seq[Attribute], child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output ++ domainAttrs
  override def producedAttributes: AttributeSet = AttributeSet(domainAttrs)
  override protected def withNewChildInternal(newChild: LogicalPlan): DomainJoin =
    copy(child = newChild)
}
