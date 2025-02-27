/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.coders.instances

import java.io.{InputStream, OutputStream}
import java.util.Collections

import com.spotify.scio.coders.Coder
import org.apache.beam.sdk.coders.Coder.NonDeterministicException
import org.apache.beam.sdk.coders.{Coder => BCoder, _}
import org.apache.beam.sdk.util.common.ElementByteSizeObserver

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.collection.{BitSet, SortedSet, TraversableOnce, mutable => m}
import scala.collection.convert.Wrappers

import scala.util.Try

private object UnitCoder extends AtomicCoder[Unit] {
  override def encode(value: Unit, os: OutputStream): Unit = ()
  override def decode(is: InputStream): Unit = ()
}

private object NothingCoder extends AtomicCoder[Nothing] {
  override def encode(value: Nothing, os: OutputStream): Unit = ()
  override def decode(is: InputStream): Nothing = ??? // can't possibly happen
}

/**
 * Most Coders TupleX are derived by Magnolia but we specialize Coder[(A, B)] for
 * performance reasons given that pairs are really common and used in groupBy operations.
 */
private final class PairCoder[A, B](ac: BCoder[A], bc: BCoder[B]) extends AtomicCoder[(A, B)] {

  @inline def onErrorMsg[T](msg: => (String, String))(f: => T): T =
    try {
      f
    } catch {
      case e: Exception =>
        throw new RuntimeException(
          s"Exception while trying to `${msg._1}` an instance of Tuple2:" +
            s" Can't decode field ${msg._2}",
          e
        )
    }

  override def encode(value: (A, B), os: OutputStream): Unit = {
    onErrorMsg("encode" -> "_1")(ac.encode(value._1, os))
    onErrorMsg("encode" -> "_2")(bc.encode(value._2, os))
  }
  override def decode(is: InputStream): (A, B) = {
    val _1 = onErrorMsg("decode" -> "_1")(ac.decode(is))
    val _2 = onErrorMsg("decode" -> "_2")(bc.decode(is))
    (_1, _2)
  }

  override def toString: String =
    s"PairCoder(_1 -> $ac, _2 -> $bc)"

  // delegate methods for determinism and equality checks

  override def verifyDeterministic(): Unit = {
    val cs = List("_1" -> ac, "_2" -> bc)
    val problems = cs.toList.flatMap {
      case (label, c) =>
        try {
          c.verifyDeterministic()
          Nil
        } catch {
          case e: NonDeterministicException =>
            val reason = s"field $label is using non-deterministic $c"
            List(reason -> e)
        }
    }

    problems match {
      case (_, e) :: _ =>
        val reasons = problems.map { case (reason, _) => reason }
        throw new NonDeterministicException(this, reasons.asJava, e)
      case Nil =>
    }
  }

  override def consistentWithEquals(): Boolean =
    ac.consistentWithEquals() && bc.consistentWithEquals()

  override def structuralValue(value: (A, B)): AnyRef =
    (ac.structuralValue(value._1), bc.structuralValue(value._2))

  // delegate methods for byte size estimation
  override def isRegisterByteSizeObserverCheap(value: (A, B)): Boolean =
    ac.isRegisterByteSizeObserverCheap(value._1) && bc.isRegisterByteSizeObserverCheap(value._2)

  override def registerByteSizeObserver(value: (A, B), observer: ElementByteSizeObserver): Unit = {
    ac.registerByteSizeObserver(value._1, observer)
    bc.registerByteSizeObserver(value._2, observer)
  }
}

private abstract class BaseSeqLikeCoder[M[_], T](val elemCoder: BCoder[T])(
  implicit toSeq: M[T] => TraversableOnce[T]
) extends AtomicCoder[M[T]] {
  override def getCoderArguments: java.util.List[_ <: BCoder[_]] =
    Collections.singletonList(elemCoder)

  // delegate methods for determinism and equality checks
  override def verifyDeterministic(): Unit = elemCoder.verifyDeterministic()
  override def consistentWithEquals(): Boolean = elemCoder.consistentWithEquals()
  override def structuralValue(value: M[T]): AnyRef = {
    val b = Seq.newBuilder[AnyRef]
    value.foreach(v => b += elemCoder.structuralValue(v))
    b.result()
  }

  // delegate methods for byte size estimation
  override def isRegisterByteSizeObserverCheap(value: M[T]): Boolean = false
  override def registerByteSizeObserver(value: M[T], observer: ElementByteSizeObserver): Unit = {
    if (value.isInstanceOf[Wrappers.JIterableWrapper[_]]) {
      val wrapper = value.asInstanceOf[Wrappers.JIterableWrapper[T]]
      IterableCoder.of(elemCoder).registerByteSizeObserver(wrapper.underlying, observer)
    } else {
      super.registerByteSizeObserver(value, observer)
    }
  }
}

private abstract class SeqLikeCoder[M[_], T](bc: BCoder[T])(
  implicit toSeq: M[T] => TraversableOnce[T]
) extends BaseSeqLikeCoder[M, T](bc) {
  private[this] val lc = VarIntCoder.of()
  override def encode(value: M[T], outStream: OutputStream): Unit = {
    lc.encode(value.size, outStream)
    value.foreach(bc.encode(_, outStream))
  }
  def decode(inStream: InputStream, builder: m.Builder[T, M[T]]): M[T] = {
    val size = lc.decode(inStream)
    var i = 0
    while (i < size) {
      builder += bc.decode(inStream)
      i = i + 1
    }
    builder.result()
  }

  override def toString: String =
    s"SeqLikeCoder($bc)"
}

private class OptionCoder[T](bc: BCoder[T]) extends SeqLikeCoder[Option, T](bc) {
  private[this] val bcoder = BooleanCoder.of().asInstanceOf[BCoder[Boolean]]
  override def encode(value: Option[T], os: OutputStream): Unit = {
    bcoder.encode(value.isDefined, os)
    value.foreach { bc.encode(_, os) }
  }

  override def decode(is: InputStream): Option[T] = {
    val isDefined = bcoder.decode(is)
    if (isDefined) Some(bc.decode(is)) else None
  }

  override def toString: String =
    s"OptionCoder($bc)"
}

private class SeqCoder[T](bc: BCoder[T]) extends SeqLikeCoder[Seq, T](bc) {
  override def decode(inStream: InputStream): Seq[T] = decode(inStream, Seq.newBuilder[T])
}

private class ListCoder[T](bc: BCoder[T]) extends SeqLikeCoder[List, T](bc) {
  override def decode(inStream: InputStream): List[T] = decode(inStream, List.newBuilder[T])
}

// TODO: implement chunking
private class TraversableOnceCoder[T](bc: BCoder[T]) extends SeqLikeCoder[TraversableOnce, T](bc) {
  override def decode(inStream: InputStream): TraversableOnce[T] =
    decode(inStream, Seq.newBuilder[T])
}

// TODO: implement chunking
private class IterableCoder[T](bc: BCoder[T]) extends SeqLikeCoder[Iterable, T](bc) {
  override def decode(inStream: InputStream): Iterable[T] =
    decode(inStream, Iterable.newBuilder[T])
}

private class VectorCoder[T](bc: BCoder[T]) extends SeqLikeCoder[Vector, T](bc) {
  override def decode(inStream: InputStream): Vector[T] = decode(inStream, Vector.newBuilder[T])
}

private class ArrayCoder[T: ClassTag](bc: BCoder[T]) extends SeqLikeCoder[Array, T](bc) {
  override def decode(inStream: InputStream): Array[T] = decode(inStream, Array.newBuilder[T])
}

private class ArrayBufferCoder[T](bc: BCoder[T]) extends SeqLikeCoder[m.ArrayBuffer, T](bc) {
  override def decode(inStream: InputStream): m.ArrayBuffer[T] =
    decode(inStream, m.ArrayBuffer.newBuilder[T])
}

private class BufferCoder[T](bc: BCoder[T]) extends SeqLikeCoder[m.Buffer, T](bc) {
  override def decode(inStream: InputStream): m.Buffer[T] = decode(inStream, m.Buffer.newBuilder[T])
}

private class SetCoder[T](bc: BCoder[T]) extends SeqLikeCoder[Set, T](bc) {
  override def decode(inStream: InputStream): Set[T] = decode(inStream, Set.newBuilder[T])
}

private class MutableSetCoder[T](bc: BCoder[T]) extends SeqLikeCoder[m.Set, T](bc) {
  override def decode(inStream: InputStream): m.Set[T] = decode(inStream, m.Set.newBuilder[T])
}

private class SortedSetCoder[T: Ordering](bc: BCoder[T]) extends SeqLikeCoder[SortedSet, T](bc) {
  override def decode(inStream: InputStream): SortedSet[T] =
    decode(inStream, SortedSet.newBuilder[T])
}

private class BitSetCoder extends AtomicCoder[BitSet] {
  private[this] val lc = VarIntCoder.of()

  def decode(in: InputStream): BitSet = {
    val l = lc.decode(in)
    val builder = BitSet.newBuilder
    (1 to l).foreach(_ => builder += lc.decode(in))

    builder.result()
  }

  def encode(ts: BitSet, out: OutputStream): Unit = {
    lc.encode(ts.size, out)
    ts.foreach(v => lc.encode(v, out))
  }
}

private class MapCoder[K, V](kc: BCoder[K], vc: BCoder[V]) extends AtomicCoder[Map[K, V]] {
  private[this] val lc = VarIntCoder.of()

  override def encode(value: Map[K, V], os: OutputStream): Unit = {
    lc.encode(value.size, os)
    val it = value.iterator
    while (it.hasNext) {
      val (k, v) = it.next
      kc.encode(k, os)
      vc.encode(v, os)
    }
  }

  override def decode(is: InputStream): Map[K, V] = {
    val l = lc.decode(is)
    val builder = Map.newBuilder[K, V]
    var i = 0
    while (i < l) {
      val k = kc.decode(is)
      val v = vc.decode(is)
      builder += (k -> v)
      i = i + 1
    }
    builder.result()
  }

  // delegate methods for determinism and equality checks
  override def verifyDeterministic(): Unit =
    throw new NonDeterministicException(
      this,
      "Ordering of entries in a Map may be non-deterministic."
    )
  override def consistentWithEquals(): Boolean = false

  // delegate methods for byte size estimation
  override def isRegisterByteSizeObserverCheap(value: Map[K, V]): Boolean = false
  override def registerByteSizeObserver(
    value: Map[K, V],
    observer: ElementByteSizeObserver
  ): Unit = {
    lc.registerByteSizeObserver(value.size, observer)
    value.foreach {
      case (k, v) =>
        kc.registerByteSizeObserver(k, observer)
        vc.registerByteSizeObserver(v, observer)
    }
  }

  override def toString: String =
    s"MapCoder($kc, $vc)"
}

private class MutableMapCoder[K, V](kc: BCoder[K], vc: BCoder[V]) extends AtomicCoder[m.Map[K, V]] {
  private[this] val lc = VarIntCoder.of()

  override def encode(value: m.Map[K, V], os: OutputStream): Unit = {
    lc.encode(value.size, os)
    value.foreach {
      case (k, v) =>
        kc.encode(k, os)
        vc.encode(v, os)
    }
  }

  override def decode(is: InputStream): m.Map[K, V] = {
    val l = lc.decode(is)
    val builder = m.Map.newBuilder[K, V]
    var i = 0
    while (i < l) {
      val k = kc.decode(is)
      val v = vc.decode(is)
      builder += (k -> v)
      i = i + 1
    }
    builder.result()
  }

  // delegate methods for determinism and equality checks
  override def verifyDeterministic(): Unit =
    throw new NonDeterministicException(
      this,
      "Ordering of entries in a Map may be non-deterministic."
    )
  override def consistentWithEquals(): Boolean = false

  // delegate methods for byte size estimation
  override def isRegisterByteSizeObserverCheap(value: m.Map[K, V]): Boolean = false
  override def registerByteSizeObserver(
    value: m.Map[K, V],
    observer: ElementByteSizeObserver
  ): Unit = {
    lc.registerByteSizeObserver(value.size, observer)
    value.foreach {
      case (k, v) =>
        kc.registerByteSizeObserver(k, observer)
        vc.registerByteSizeObserver(v, observer)
    }
  }

  override def toString: String =
    s"MutableMapCoder($kc, $vc)"
}

// scalastyle:off number.of.methods
trait ScalaCoders {

  implicit def charCoder: Coder[Char] =
    Coder.xmap(Coder.beam(ByteCoder.of()))(_.toChar, _.toByte)
  implicit def byteCoder: Coder[Byte] =
    Coder.beam(ByteCoder.of().asInstanceOf[BCoder[Byte]])
  implicit def stringCoder: Coder[String] =
    Coder.beam(StringUtf8Coder.of())
  implicit def shortCoder: Coder[Short] =
    Coder.beam(BigEndianShortCoder.of().asInstanceOf[BCoder[Short]])
  implicit def intCoder: Coder[Int] =
    Coder.beam(VarIntCoder.of().asInstanceOf[BCoder[Int]])
  implicit def longCoder: Coder[Long] =
    Coder.beam(BigEndianLongCoder.of().asInstanceOf[BCoder[Long]])
  implicit def floatCoder: Coder[Float] =
    Coder.beam(FloatCoder.of().asInstanceOf[BCoder[Float]])
  implicit def doubleCoder: Coder[Double] =
    Coder.beam(DoubleCoder.of().asInstanceOf[BCoder[Double]])
  implicit def booleanCoder: Coder[Boolean] =
    Coder.beam(BooleanCoder.of().asInstanceOf[BCoder[Boolean]])
  implicit def unitCoder: Coder[Unit] = Coder.beam(UnitCoder)
  implicit def nothingCoder: Coder[Nothing] = Coder.beam[Nothing](NothingCoder)

  implicit def bigIntCoder: Coder[BigInt] =
    Coder.xmap(Coder.beam(BigIntegerCoder.of()))(BigInt.apply, _.bigInteger)

  implicit def bigDecimalCoder: Coder[BigDecimal] =
    Coder.xmap(Coder.beam(BigDecimalCoder.of()))(BigDecimal.apply, _.bigDecimal)

  implicit def tryCoder[A: Coder]: Coder[Try[A]] =
    Coder.gen[Try[A]]

  implicit def eitherCoder[A: Coder, B: Coder]: Coder[Either[A, B]] =
    Coder.gen[Either[A, B]]

  implicit def optionCoder[T, S[_] <: Option[_]](implicit c: Coder[T]): Coder[S[T]] =
    Coder
      .transform(c) { bc =>
        Coder.beam(new OptionCoder[T](bc))
      }
      .asInstanceOf[Coder[S[T]]]

  implicit def noneCoder: Coder[None.type] =
    optionCoder[Nothing, Option](nothingCoder).asInstanceOf[Coder[None.type]]

  implicit def bitSetCoder: Coder[BitSet] = Coder.beam(new BitSetCoder)

  implicit def seqCoder[T: Coder]: Coder[Seq[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new SeqCoder[T](bc))
    }

  import shapeless.Strict
  implicit def pairCoder[A, B](implicit CA: Strict[Coder[A]], CB: Strict[Coder[B]]): Coder[(A, B)] =
    Coder.transform(CA.value) { ac =>
      Coder.transform(CB.value) { bc =>
        Coder.beam(new PairCoder[A, B](ac, bc))
      }
    }

  // TODO: proper chunking implementation
  implicit def iterableCoder[T: Coder]: Coder[Iterable[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new IterableCoder[T](bc))
    }

  implicit def throwableCoder[T <: Throwable: ClassTag]: Coder[T] =
    Coder.kryo[T]

  // specialized coder. Since `::` is a case class, Magnolia would derive an incorrect one...
  implicit def listCoder[T: Coder]: Coder[List[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new ListCoder[T](bc))
    }

  implicit def traversableOnceCoder[T: Coder]: Coder[TraversableOnce[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new TraversableOnceCoder[T](bc))
    }

  implicit def setCoder[T: Coder]: Coder[Set[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new SetCoder[T](bc))
    }

  implicit def mutableSetCoder[T: Coder]: Coder[m.Set[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new MutableSetCoder[T](bc))
    }

  implicit def vectorCoder[T: Coder]: Coder[Vector[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new VectorCoder[T](bc))
    }

  implicit def arrayBufferCoder[T: Coder]: Coder[m.ArrayBuffer[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new ArrayBufferCoder[T](bc))
    }

  implicit def bufferCoder[T: Coder]: Coder[m.Buffer[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new BufferCoder[T](bc))
    }

  implicit def listBufferCoder[T: Coder]: Coder[m.ListBuffer[T]] =
    Coder.xmap(bufferCoder[T])(m.ListBuffer(_: _*), identity)

  implicit def arrayCoder[T: Coder: ClassTag]: Coder[Array[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new ArrayCoder[T](bc))
    }

  implicit def arrayByteCoder: Coder[Array[Byte]] =
    Coder.beam(ByteArrayCoder.of())

  implicit def wrappedArrayCoder[T: Coder: ClassTag](
    implicit wrap: Array[T] => m.WrappedArray[T]
  ): Coder[m.WrappedArray[T]] =
    Coder.xmap(Coder[Array[T]])(wrap, _.array)

  implicit def mutableMapCoder[K: Coder, V: Coder]: Coder[m.Map[K, V]] =
    Coder.transform(Coder[K]) { kc =>
      Coder.transform(Coder[V]) { vc =>
        Coder.beam(new MutableMapCoder[K, V](kc, vc))
      }
    }

  implicit def mapCoder[K: Coder, V: Coder]: Coder[Map[K, V]] =
    Coder.transform(Coder[K]) { kc =>
      Coder.transform(Coder[V]) { vc =>
        Coder.beam(new MapCoder[K, V](kc, vc))
      }
    }

  implicit def sortedSetCoder[T: Coder: Ordering]: Coder[SortedSet[T]] =
    Coder.transform(Coder[T]) { bc =>
      Coder.beam(new SortedSetCoder[T](bc))
    }

  // implicit def enumerationCoder[E <: Enumeration]: Coder[E#Value] = ???
}
