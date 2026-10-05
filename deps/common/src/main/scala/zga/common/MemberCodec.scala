package zga.common

/**
 * Codec for one parameter of a generated case class.
 *
 * A parameter maps either to a single proto field, encoded by a [[FieldCodec]],
 * or to a proto `oneof`, encoded by a [[OneofCodec]]. A oneof parameter is an
 * `Option` of its oneof type, which a [[FieldCodec]] cannot encode because the
 * selected member field depends on the value.
 */
sealed trait MemberCodec[T]

object MemberCodec {

  given fromField[T](using codec: FieldCodec[T]): MemberCodec[T] = FieldMemberCodec(codec)

  given fromOneof[T <: Oneof](using codec: OneofCodec[T]): MemberCodec[Option[T]] = OneofMemberCodec(codec)
}

final case class FieldMemberCodec[T](codec: FieldCodec[T]) extends MemberCodec[T]

final case class OneofMemberCodec[T <: Oneof](codec: OneofCodec[T]) extends MemberCodec[Option[T]]
