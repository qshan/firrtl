// See LICENSE for license details.

package firrtl.passes

import firrtl._
import firrtl.ir._
import firrtl.PrimOps._
import firrtl.traversals.Foreachers._
import firrtl.Utils._
import firrtl.constraint.IsKnown
import firrtl.annotations.{CircuitTarget, ModuleTarget, Target, TargetToken}
import firrtl.options.{Dependency, PreservesAll}

object CheckWidths extends Pass with PreservesAll[Transform] {

  override def prerequisites = Dependency[passes.InferWidths] +: firrtl.stage.Forms.WorkingIR

  override def optionalPrerequisiteOf = Seq(Dependency[transforms.InferResets])

  /** The maximum allowed width for any circuit element */
  val MaxWidth = 1000000
  val DshlMaxWidth = getUIntWidth(MaxWidth)
  class UninferredWidth (info: Info, target: String) extends PassException(
    s"""|$info : Uninferred width for target below.serialize}. (Did you forget to assign to it?)
        |$target""".stripMargin)
  class UninferredBound (info: Info, target: String, bound: String) extends PassException(
    s"""|$info : Uninferred $bound bound for target. (Did you forget to assign to it?)
        |$target""".stripMargin)
  class InvalidRange (info: Info, target: String, i: IntervalType) extends PassException(
    s"""|$info : Invalid range ${i.serialize} for target below. (Are the bounds valid?)
        |$target""".stripMargin)
  class WidthTooSmall(info: Info, mname: String, b: BigInt) extends PassException(
    s"$info : [target $mname]  Width too small for constant $b.")
  class WidthTooBig(info: Info, mname: String, b: BigInt) extends PassException(
    s"$info : [target $mname]  Width $b greater than max allowed width of $MaxWidth bits")
  class DshlTooBig(info: Info, mname: String) extends PassException(
    s"$info : [target $mname]  Width of dshl shift amount cannot be larger than $DshlMaxWidth bits.")
  class MultiBitAsClock(info: Info, mname: String) extends PassException(
    s"$info : [target $mname]  Cannot cast a multi-bit signal to a Clock.")
  class MultiBitAsAsyncReset(info: Info, mname: String) extends PassException(
    s"$info : [target $mname]  Cannot cast a multi-bit signal to an AsyncReset.")
  class NegWidthException(info:Info, mname: String) extends PassException(
    s"$info: [target $mname] Width cannot be negative or zero.")
  class BitsWidthException(info: Info, mname: String, hi: BigInt, width: BigInt, exp: String) extends PassException(
    s"$info: [target $mname] High bit $hi in bits operator is larger than input width $width in $exp.")
  class HeadWidthException(info: Info, mname: String, n: BigInt, width: BigInt) extends PassException(
    s"$info: [target $mname] Parameter $n in head operator is larger than input width $width.")
  class TailWidthException(info: Info, mname: String, n: BigInt, width: BigInt) extends PassException(
    s"$info: [target $mname] Parameter $n in tail operator is larger than input width $width.")
  class AttachWidthsNotEqual(info: Info, mname: String, eName: String, source: String) extends PassException(
    s"$info: [target $mname] Attach source $source and expression $eName must have identical widths.")
  class DisjointSqueeze(info: Info, mname: String, squeeze: DoPrim)
    extends PassException({
      val toSqz = squeeze.args.head.serialize
      val toSqzTpe = squeeze.args.head.tpe.serialize
      val sqzTo = squeeze.args(1).serialize
      val sqzToTpe = squeeze.args(1).tpe.serialize
      s"$info: [module $mname] Disjoint squz currently unsupported: $toSqz:$toSqzTpe cannot be squeezed with $sqzTo's type $sqzToTpe"
    })

  def run(c: Circuit): Circuit = {
    val errors = new Errors()

    def check_width_w(info: Info, target: Target, t: Type)(w: Width): Unit = {
      (w, t) match {
        case (IntWidth(width), _) if width >= MaxWidth =>
          errors.append(new WidthTooBig(info, target.serialize, width))
        case (w: IntWidth, f: FixedType) if (w.width < 0 && w.width == f.width) =>
          errors.append(new NegWidthException(info, target.serialize))
        case (_: IntWidth, _) =>
        case _ =>
          errors.append(new UninferredWidth(info, target.prettyPrint("    ")))
      }
    }

    def hasWidth(tpe: Type): Boolean = tpe match {
      case GroundType(IntWidth(w)) => true
      case GroundType(_) => false
      case _ => throwInternalError(s"hasWidth - $tpe")
    }

    def check_width_t(info: Info, target: Target)(t: Type): Unit = {
      t match {
        case tt: BundleType => tt.fields.foreach(check_width_f(info, target))
        //Supports when l = u (if closed)
        case i@IntervalType(Closed(l), Closed(u), IntWidth(_)) if l <= u => i
        case i:IntervalType if i.range == Some(Nil) =>
          errors.append(new InvalidRange(info, target.prettyPrint("    "), i))
          i
        case i@IntervalType(KnownBound(l), KnownBound(u), IntWidth(p)) if l >= u =>
          errors.append(new InvalidRange(info, target.prettyPrint("    "), i))
          i
        case i@IntervalType(KnownBound(_), KnownBound(_), IntWidth(_)) => i
        case i@IntervalType(_: IsKnown, _, _) =>
          errors.append(new UninferredBound(info, target.prettyPrint("    "), "upper"))
          i
        case i@IntervalType(_, _: IsKnown, _) =>
          errors.append(new UninferredBound(info, target.prettyPrint("    "), "lower"))
          i
        case i@IntervalType(_, _, _) =>
          errors.append(new UninferredBound(info, target.prettyPrint("    "), "lower"))
          errors.append(new UninferredBound(info, target.prettyPrint("    "), "upper"))
          i
        case tt => tt foreach check_width_t(info, target)
      }
      t foreach check_width_w(info, target, t)
    }

    def check_width_f(info: Info, target: Target)(f: Field): Unit =
      check_width_t(info, target.modify(tokens = target.tokens :+ TargetToken.Field(f.name)))(f.tpe)

    def check_width_e(info: Info, target: Target)(e: Expression): Unit = {
      var stack = List(e)
      while (stack.nonEmpty) {
        val currentExp = stack.head
        stack = stack.tail
        currentExp match {
          case e @ UIntLiteral(v, w: IntWidth) if math.max(1, v.bitLength) > w.width =>
            errors.append(new WidthTooSmall(info, target.serialize, v))
          case e @ SIntLiteral(v, w: IntWidth) if v.bitLength + 1 > w.width =>
            errors.append(new WidthTooSmall(info, target.serialize, v))
          case e @ DoPrim(op, Seq(a, b), _, tpe) =>
            // Binary primop
            stack = a :: b :: stack
            (op, a.tpe, b.tpe) match {
              case (Squeeze, IntervalType(Closed(la), Closed(ua), _), IntervalType(Closed(lb), Closed(ub), _)) if (ua < lb) || (ub < la) =>
                errors.append(new DisjointSqueeze(info, target.serialize, e))
              case (Dshl, at, bt) if (hasWidth(at) && bitWidth(bt) >= DshlMaxWidth) =>
                errors.append(new DshlTooBig(info, target.serialize))
              case _ =>
            }
          case e @ DoPrim(op, Seq(a), consts, _) =>
            stack = a :: stack
            (op, consts) match {
              case (Bits, Seq(hi, lo)) if (hasWidth(a.tpe) && bitWidth(a.tpe) <= hi) =>
                errors.append(new BitsWidthException(info, target.serialize, hi, bitWidth(a.tpe), e.serialize))
              case (Head, Seq(n)) if (hasWidth(a.tpe) && bitWidth(a.tpe) < n) =>
                errors.append(new HeadWidthException(info, target.serialize, n, bitWidth(a.tpe)))
              case (Tail, Seq(n)) if (hasWidth(a.tpe) && bitWidth(a.tpe) < n) =>
                errors.append(new TailWidthException(info, target.serialize, n, bitWidth(a.tpe)))
              case (AsClock, _) if (bitWidth(a.tpe) != 1) =>
                errors.append(new MultiBitAsClock(info, target.serialize))
              case (AsAsyncReset, _) if (bitWidth(a.tpe) != 1) =>
                errors.append(new MultiBitAsAsyncReset(info, target.serialize))
              case _ =>
            }
          case WSubField(e, _, _, _) => stack = e :: stack
          case WSubIndex(e, _, _, _) => stack = e :: stack
          case WSubAccess(e, _, _, _) => stack = e :: stack
          case Mux(c, t, f, _) => stack = c :: t :: f :: stack
          case ValidIf(c, v, _) => stack = c :: v :: stack
          case _ =>
        }
      }
    }

    def check_width_s(minfo: Info, target: ModuleTarget)(s: Statement): Unit = {
      val info = get_info(s) match { case NoInfo => minfo case x => x }
      val subRef = s match { case sx: HasName => target.ref(sx.name) case _ => target }
      s foreach check_width_e(info, target)
      s foreach check_width_s(info, target)
      s foreach check_width_t(info, subRef)
      s match {
        case Attach(infox, exprs) =>
          exprs.tail.foreach ( e =>
            if (bitWidth(e.tpe) != bitWidth(exprs.head.tpe))
              errors.append(new AttachWidthsNotEqual(infox, target.serialize, e.serialize, exprs.head.serialize))
          )
        case sx: DefRegister =>
          sx.reset.tpe match {
            case UIntType(IntWidth(w)) if w == 1 =>
            case AsyncResetType =>
            case ResetType =>
            case _ => errors.append(new CheckTypes.IllegalResetType(info, target.serialize, sx.name))
          }
          if(!CheckTypes.validConnect(sx.tpe, sx.init.tpe)) {
            val conMsg = sx.copy(info = NoInfo).serialize
            errors.append(new CheckTypes.InvalidConnect(info, target.module, conMsg, WRef(sx), sx.init))
          }
        case _ =>
      }
    }

    def check_width_p(minfo: Info, target: ModuleTarget)(p: Port): Unit = check_width_t(p.info, target.ref(p.name))(p.tpe)

    def check_width_m(circuit: CircuitTarget)(m: DefModule): Unit = {
      m foreach check_width_p(m.info, circuit.module(m.name))
      m foreach check_width_s(m.info, circuit.module(m.name))
    }

    c.modules foreach check_width_m(CircuitTarget(c.main))
    errors.trigger()
    c
  }
}
