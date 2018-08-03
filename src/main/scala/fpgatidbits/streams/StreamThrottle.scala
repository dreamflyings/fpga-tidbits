package fpgatidbits.streams

import Chisel._

// throttle the requests passing from a producer to a consumer, controlled
// by an explicit signal

class StreamThrottle[T <: Data](gen: T) extends Module {
  val io = new Bundle {
    val in = Decoupled(gen).flip    // input stream
    val out = Decoupled(gen)        // output stream
    val throttle = Bool(INPUT)      // stop input to output when this is high
  }
  out.bits := in.bits
  out.valid := in.valid & !io.throttle
  in.ready := out.ready & !io.throttle
}

object StreamThrottle {
  def apply[T <: Data](in: DecoupledIO[T], throttle: Bool) = {
    val m = Module(new StreamThrottle(gen = in.bits)).io
    in <> m.in
    m.throttle := throttle
    m.out
  }
}
