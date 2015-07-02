package TidbitsDMA

import Chisel._


class WriteReqGen(p: MemReqParams, chanID: Int) extends ReadReqGen(p, chanID) {
  // force single beat per burst for now
  // TODO support write bursts -- needs support in interleaver
  val bytesPerBurst = 1 * bytesPerBeat
  io.reqs.bits.isWrite := Bool(true)
}

// a generic memory request generator,
// only for contiguous accesses for now (no indirects, no strides)
// only burst-aligned addresses and sizes (no error checking!)
// TODO do we want to support unaligned/sub-word accesses?
class ReadReqGen(p: MemReqParams, chanID: Int) extends Module {
  val reqGenParams = p
  val io = new Bundle {
    // control/status interface
    val ctrl = new ReqGenCtrl(p.addrWidth)
    val stat = new ReqGenStatus()
    // requests
    val reqs = Decoupled(new GenericMemoryRequest(p))
  }
  // shorthands for convenience
  val bytesPerBeat = (p.dataWidth/8)
  val bytesPerBurst = p.beatsPerBurst * bytesPerBeat
  // state machine definitions & internal registers
  val sIdle :: sRun :: sFinished :: Nil = Enum(UInt(), 3)
  val regState = Reg(init = UInt(sIdle))
  val regAddr = Reg(init = UInt(0, p.addrWidth))
  val regBytesLeft = Reg(init = UInt(0, p.addrWidth))
  // default outputs
  io.stat.finished := Bool(false)
  io.stat.active := (regState != sIdle)
  io.reqs.valid := Bool(false)
  io.reqs.bits.channelID := UInt(chanID)
  io.reqs.bits.isWrite := Bool(false)
  io.reqs.bits.addr := regAddr
  io.reqs.bits.metaData := UInt(0)
  io.reqs.bits.numBytes := UInt(bytesPerBurst)

  switch(regState) {
      is(sIdle) {
        regAddr := io.ctrl.baseAddr
        regBytesLeft := io.ctrl.byteCount
        when (io.ctrl.start) { regState := sRun }
      }

      is(sRun) {
        when (regBytesLeft === UInt(0)) { regState := sFinished }
        .elsewhen (!io.ctrl.throttle) {
          // issue the current request
          io.reqs.valid := Bool(true)
          when (io.reqs.ready) {
            // next request: update address & left request count
            regAddr := regAddr + UInt(bytesPerBurst)
            regBytesLeft := regBytesLeft - UInt(bytesPerBurst)
          }
        }
      }

      is(sFinished) {
        io.stat.finished := Bool(true)
        when (!io.ctrl.start) { regState := sIdle }
      }
  }
}

class TestReadReqGenWrapper() extends Module {
  val p = new MemReqParams(48, 64, 4, 1, 8)

  val io = new Bundle {
    val ctrl = new ReqGenCtrl(p.addrWidth)
    val stat = new ReqGenStatus()
    val reqQOut = Decoupled(new GenericMemoryRequest(p))
  }

  val dut = Module(new ReadReqGen(p, 0))
  val reqQ = Module(new Queue(new GenericMemoryRequest(p), 4096))
  dut.io.reqs <> reqQ.io.enq
  reqQ.io.deq <> io.reqQOut
  io.ctrl <> dut.io.ctrl
  io.stat <> dut.io.stat
}

class TestReadReqGen(c: TestReadReqGenWrapper) extends Tester(c) {

  c.io.reqQOut.ready := Bool(false)

  val byteCount = 1024
  val baseAddr = 100

  val expectedReqCount = byteCount / (c.dut.bytesPerBurst)

  def waitUntilFinished(): Unit = {
    while(peek(c.io.stat.finished) != 1) {
      peek(c.reqQ.io.enq.valid)
      peek(c.reqQ.io.enq.bits)
      step(1)
      peek(c.reqQ.io.count)
    }
  }

  // Test 1: check request count and addresses, no throttling
  // set up the reqgen
  poke(c.io.ctrl.start, 0)
  poke(c.io.ctrl.throttle, 0)
  poke(c.io.ctrl.baseAddr, baseAddr)
  poke(c.io.ctrl.byteCount, byteCount)
  poke(c.io.reqQOut.ready, 0)
  step(1)
  expect(c.io.stat.finished, 0)
  expect(c.io.stat.active, 0)
  // activate and checki
  poke(c.io.ctrl.start, 1)
  step(1)
  expect(c.io.stat.finished, 0)
  expect(c.io.stat.active, 1)
  waitUntilFinished()
  // check number of emitted requests
  expect(c.reqQ.io.count, expectedReqCount)
  var expAddr = baseAddr
  // pop requests and check addresses
  while(peek(c.io.reqQOut.valid) == 1) {
    expect(c.io.reqQOut.bits.isWrite, 0)
    expect(c.io.reqQOut.bits.addr, expAddr)
    expect(c.io.reqQOut.bits.numBytes, c.dut.bytesPerBurst)
    poke(c.io.reqQOut.ready, 1)
    step(1)
    expAddr += c.dut.bytesPerBurst
  }
  // deinitialize and check
  poke(c.io.ctrl.start, 0)
  poke(c.io.reqQOut.ready, 0)
  step(1)
  expect(c.io.stat.finished, 0)
  expect(c.io.stat.active, 0)
  expect(c.reqQ.io.count, 0)

  // Test 2: repeat Test 1 with throttling
  poke(c.io.ctrl.start, 1)
  poke(c.io.ctrl.throttle, 1)
  step(1)
  expect(c.io.stat.finished, 0)
  expect(c.io.stat.active, 1)
  step(10)
  // verify that no requests appear
  expect(c.reqQ.io.count, 0)
  // remove throttling
  poke(c.io.ctrl.throttle, 0)
  waitUntilFinished()
  // check number of emitted requests
  expect(c.reqQ.io.count, expectedReqCount)
  expAddr = baseAddr
  // pop requests and check addresses
  while(peek(c.io.reqQOut.valid) == 1) {
    expect(c.io.reqQOut.bits.isWrite, 0)
    expect(c.io.reqQOut.bits.addr, expAddr)
    expect(c.io.reqQOut.bits.numBytes, c.dut.bytesPerBurst)
    poke(c.io.reqQOut.ready, 1)
    step(1)
    expAddr += c.dut.bytesPerBurst
  }
  // deinitialize and check
  poke(c.io.ctrl.start, 0)
  poke(c.io.reqQOut.ready, 0)
  step(1)
  expect(c.io.stat.finished, 0)
  expect(c.io.stat.active, 0)
  expect(c.reqQ.io.count, 0)
}