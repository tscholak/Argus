package argus.macros

import scala.reflect.api.Universe

/**
  * @author Aish Fenton.
  */
class CirceCodecBuilder[U <: Universe](val u: U) extends CodecBuilder {

  import u._
  import helpers._

  val imports =
    q"import cats.syntax.either._" ::
    q"import io.circe._" ::
    q"import io.circe.syntax._" ::
    Nil

  def inEncoder(typ: Tree) = tq"Encoder[$typ]"

  def inDecoder(typ: Tree) = tq"Decoder[$typ]"

  def constants = anyEncoder :: anyDecoder :: dateTimeEncoder :: dateTimeDecoder :: Nil

  val anyEncoder = q"""
    def anyEncoder: Encoder[Any] = Encoder.instance((a: Any) => a match {
      case null =>        Json.Null
      case b: Boolean =>  b.asJson
      case b: Byte =>     b.asJson
      case s: Short =>    s.asJson
      case i: Int =>      i.asJson
      case l: Long =>     l.asJson
      case f: Float =>    f.asJson
      case d: Double =>   d.asJson
      case s: String =>   s.asJson
      case u: java.util.UUID => u.asJson
      case a: Array[Boolean] @unchecked => a.asJson
      case a: Array[Byte]    @unchecked => a.asJson
      case a: Array[Short]   @unchecked => a.asJson
      case a: Array[Int]     @unchecked => a.asJson
      case a: Array[Long]    @unchecked => a.asJson
      case a: Array[Float]   @unchecked => a.asJson
      case a: Array[Double]  @unchecked => a.asJson
      case s: Array[Any]     @unchecked => s.asJson(Encoder.encodeTraversableOnce(anyEncoder, implicitly))
      case s: Seq[Any]       @unchecked => s.asJson(Encoder.encodeTraversableOnce(anyEncoder, implicitly))
      case ma: Map[String, Any] @unchecked => ma.asJson(Encoder.encodeMapLike(KeyEncoder.encodeKeyString, anyEncoder))
    })
  """

  val anyDecoder = q"""
    def anyDecoder: Decoder[Any] = Decoder.instance((h: HCursor) => h.focus.get match {
      case n if n.isNull =>    null
      case n if n.isNumber =>  n.as[Double]
      case b if b.isBoolean => b.as[Boolean]
      case s if s.isString =>  s.as[String]
      case o if o.isObject =>  o.as[Map[String, Any]](Decoder.decodeMapLike(KeyDecoder.decodeKeyString, anyDecoder, Map.canBuildFrom))
      case a if a.isArray =>   a.as[List[Any]](Decoder.decodeCanBuildFrom(anyDecoder, List.canBuildFrom[Any]))
    })
  """

  val dateTimeEncoder = q"""
    implicit val dateTimeEncoder: Encoder[org.joda.time.DateTime] = Encoder.instance(dt => dt.toString(org.joda.time.format.ISODateTimeFormat.dateTime().withOffsetParsed).asJson);
  """

  val dateTimeDecoder = q"""
    implicit val dateTimeDecoder: Decoder[org.joda.time.DateTime] = Decoder.instance((c: HCursor) =>
      for {
        json <- c.as[Json]
        strJson <- json.asString.map(Either.right).getOrElse(Either.left(DecodingFailure("DateTime", c.history)))
        dt <- try {
          Either.right(org.joda.time.DateTime.parse(json.asString.get, org.joda.time.format.ISODateTimeFormat.dateTime().withOffsetParsed))
        } catch {
          case error: Exception => Either.left(DecodingFailure(error.getMessage, c.history))
        }
      } yield dt
    )
  """

  def mkAnyWrapperEncoder(typ: Tree) = q"""
    Encoder.instance((wrapper: $typ) => {
      wrapper.x.asJson(anyEncoder)
    })
  """

  def mkAnyWrapperDecoder(typ: Tree) = q"""
    Decoder.instance((h: HCursor) => {
      h.as[Any](anyDecoder).map(${companionForType(typ)}(_))
    })
  """

  def mkUnionEncoder(typ: Tree, subTypes: List[(Tree, Tree)]): Tree = {
    val caseDefs = subTypes.map { case(rawType, unionType) =>
      cq"ut: $unionType => ut.x.asJson"
    }

    val encDef = q"""
    Encoder.instance { case ..$caseDefs }
    """
    encDef
  }

  def mkUnionDecoder(typ: Tree, subTypes: List[(Tree, Tree)]): Tree = {
    val (rt, ut) = subTypes.head
    val asDefs: Tree = subTypes.tail.foldLeft(q"c.as[$rt].map((x) => ${companionForType(ut)}(x))") {
      case (s:Tree, (rt:Tree, ut: Tree)) => q"$s.orElse(c.as[$rt].map((x) => ${companionForType(ut)}(x)))"
    }

    q"""
    Decoder.instance((c: HCursor) => { $asDefs })
    """
  }

  def mkEnumEncoder(typ: Tree, subTermPairs: List[(String, Tree)]): Tree = {
    // We can ignore subtypes and just encode based on supertype here
    q"""Encoder.instance((e: $typ) => parser.parse(e.json).toOption.get)"""
  }

  def mkEnumDecoder(typ: Tree, subTermPairs: List[(String, Tree)]): Tree = {
    val caseDefs = subTermPairs.map { case(jsonStr, subTerm) =>
      cq"j if j == parser.parse($jsonStr).toOption.get => Either.right($subTerm)"
    }

    val decDef = q"""
    Decoder.instance((c: HCursor) => for {
      json <- c.as[Json]
      singleton <- json match { case ..$caseDefs; case _ => Either.left(DecodingFailure("Couldn't find enum:" + json.toString, c.history)) }
    } yield singleton)
    """

    decDef
  }

  def mkEncoder(typ: Tree, fields: List[Tree]): Tree = {
    val mappings = fields.map { case q"$_ val $name: $_ = $_" =>
      q"${name.toString} -> cc.$name.asJson"
    }

    val encDef = q"""
    Encoder.instance((cc: $typ) => Json.obj( ..$mappings ))
    """

    encDef
  }

  def mkDecoder(typ: Tree, fields: List[Tree]): Tree = {
    val (forVals, vals) = fields.foldLeft((List[Tree](), List[TermName]())) {
      case ((fs, vs), q"$_ val $name: $tname = $_") =>
        val forVal = fq"$name <- c.downField(${name.toString}).as[$tname]"
        (fs :+ forVal, vs :+ name)
    }

    val decDef = q"""
    Decoder.instance((c: HCursor) => for( ..$forVals ) yield ${companionForType(typ)}( ..$vals ))
    """

    decDef
  }

}
