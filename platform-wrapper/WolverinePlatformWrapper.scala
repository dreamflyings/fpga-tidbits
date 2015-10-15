package TidbitsPlatformWrapper

import Chisel._
import TidbitsDMA._
import TidbitsRegFile._

// Wrapper for the Convey Wolverine hardware platform
// (made for WX690T, may work for others)

// TODO implement muxing for memory ports?

trait WX690TParams extends PlatformWrapperParams {
  val platformName = "wx690t"
  val memAddrBits = 48
  val memDataBits = 64
  val memIDBits = 32
  val memMetaBits = 1
  val csrDataBits = 64
}

class WolverinePlatformWrapper(p: WX690TParams,
instFxn: PlatformWrapperParams => GenericAccelerator)
extends PlatformWrapper(p, instFxn) {
  // the Convey wrapper itself always expects at least one memory port
  // if no mem ports are desired, we still create one and drive outputs to 0
  val numCalculatedMemPorts = if(p.numMemPorts == 0) 1 else p.numMemPorts

  val io = new ConveyPersonalityVerilogIF(numCalculatedMemPorts, p.memIDBits)
  // rename io signals to be compatible with Verilog template
  io.renameSignals()

  // the base class instantiates:
  // - the accelerator as "accel"
  // - the register file as "regFile"

  // wiring up register file access
  io.dispAegCnt := UInt(p.numRegs)
  io.dispException := UInt(0) // TODO Convey: support exceptions
  io.dispRtnValid := regFile.extIF.readData.valid
  io.dispRtnData := regFile.extIF.readData.bits
  regFile.extIF.cmd.bits.regID := io.dispRegID
  regFile.extIF.cmd.bits.read := io.dispRegRead
  regFile.extIF.cmd.bits.write := io.dispRegWrite
  regFile.extIF.cmd.bits.writeData := io.dispRegWrData
  regFile.extIF.cmd.valid := io.dispRegRead || io.dispRegWrite

  // TODO Convey: add support for Convey's CSR interface, disabled for now
  io.csrReadAck := Bool(false)
  io.csrReadData := UInt(0)

  // instruction dispatch
  accel.start := io.dispInstValid
  io.dispIdle := accel.idle
  io.dispStall := !accel.idle

  // TODO wire up memory port adapters
  if (p.numMemPorts == 0) {
    // plug unused memory port (remember we need at least one)
    io.mcReqValid := UInt(0)
    io.mcReqRtnCtl := UInt(0)
    io.mcReqData := UInt(0)
    io.mcReqAddr := UInt(0)
    io.mcReqSize := UInt(0)
    io.mcReqCmd := UInt(0)
    io.mcReqSCmd := UInt(0)
    io.mcResStall := UInt(0)
    io.mcReqFlush := UInt(0)
  } else {
    throw new Exception("Convey wrappers don't yet support memory ports")
  }

  // print some warnings to remind the user to change the cae_pers.v values
  println(s"====> Remember to set NUM_MC_PORTS=$numCalculatedMemPorts in cae_pers.v")
  val numRtnCtlBits = p.memIDBits
  println(s"====> Remember to set RTNCTL_WIDTH=$numRtnCtlBits in cae_pers.v")
}



// various interface definitions for Convey systems

// dispatch slave interface
// for accepting instructions and AEG register operations
class DispatchSlaveIF() extends Bundle {
  // instruction opcode
  // note that this interface is defined as stall-valid instead of ready-valid
  // by Convey, so the ready signal should be inverted from stall
  val instr       = Decoupled(UInt(width = 5)).flip
  // register file access
  val aeg         = new RegFileSlaveIF(18, 64)
  // output for signalling instruction exceptions
  val exception   = UInt(OUTPUT, width = 16)

  override def clone = { new DispatchSlaveIF().asInstanceOf[this.type] }
}

// command (request) bundle for memory read/writes
class ConveyMemRequest(rtnCtlBits: Int, addrBits: Int, dataBits: Int) extends Bundle {
  val rtnCtl      = UInt(width = rtnCtlBits)
  val writeData   = UInt(width = dataBits)
  val addr        = UInt(width = addrBits)
  val size        = UInt(width = 2)
  val cmd         = UInt(width = 3)
  val scmd        = UInt(width = 4)

  override def clone = {
    new ConveyMemRequest(rtnCtlBits, addrBits, dataBits).asInstanceOf[this.type] }
}

// response bundle for return read data or write completes (?)
class ConveyMemResponse(rtnCtlBits: Int, dataBits: Int) extends Bundle {
  val rtnCtl      = UInt(width = rtnCtlBits)
  val readData    = UInt(width = dataBits)
  val cmd         = UInt(width = 3)
  val scmd        = UInt(width = 4)

  override def clone = {
    new ConveyMemResponse(rtnCtlBits, dataBits).asInstanceOf[this.type] }
}

// memory port master interface
class ConveyMemMasterIF(rtnCtlBits: Int) extends Bundle {
  // note that req and rsp are defined by Convey as stall/valid interfaces
  // (instead of ready/valid as defined here) -- needs adapter
  val req         = Decoupled(new ConveyMemRequest(rtnCtlBits, 48, 64))
  val rsp         = Decoupled(new ConveyMemResponse(rtnCtlBits, 64)).flip
  val flushReq    = Bool(OUTPUT)
  val flushOK     = Bool(INPUT)

  override def clone = {
    new ConveyMemMasterIF(rtnCtlBits).asInstanceOf[this.type] }
}

// interface for a Convey personality (for use in Chisel)
class ConveyPersonalityIF(numMemPorts: Int, rtnCtlBits: Int) extends Bundle {
  val disp = new DispatchSlaveIF()
  val csr  = new RegFileSlaveIF(16, 64)
  val mem  = Vec.fill(numMemPorts) { new ConveyMemMasterIF(rtnCtlBits) }

  override def clone = {
    new ConveyPersonalityIF(numMemPorts, rtnCtlBits).asInstanceOf[this.type] }
}

// interface definition for the Convey WX690T (Verilog) personality IF
// this is instantiated inside the cae_pers.v file (remember to set the
// correct number of memory ports and RTNCTL_WIDTH in the cae_pers.v)
class ConveyPersonalityVerilogIF(numMemPorts: Int, rtnctl: Int) extends Bundle {
  // dispatch interface
  val dispInstValid = Bool(INPUT)
  val dispInstData  = UInt(INPUT, width = 5)
  val dispRegID     = UInt(INPUT, width = 18)
  val dispRegRead   = Bool(INPUT)
  val dispRegWrite  = Bool(INPUT)
  val dispRegWrData = UInt(INPUT, width = 64)
  val dispAegCnt    = UInt(OUTPUT, width = 18)
  val dispException = UInt(OUTPUT, width = 16)
  val dispIdle      = Bool(OUTPUT)
  val dispRtnValid  = Bool(OUTPUT)
  val dispRtnData   = UInt(OUTPUT, width = 64)
  val dispStall     = Bool(OUTPUT)
  // memory controller interface
  // request
  val mcReqValid    = UInt(OUTPUT, width = numMemPorts)
  val mcReqRtnCtl   = UInt(OUTPUT, width = rtnctl*numMemPorts)
  val mcReqData     = UInt(OUTPUT, width = 64*numMemPorts)
  val mcReqAddr     = UInt(OUTPUT, width = 48*numMemPorts)
  val mcReqSize     = UInt(OUTPUT, width = 2*numMemPorts)
  val mcReqCmd      = UInt(OUTPUT, width = 3*numMemPorts)
  val mcReqSCmd     = UInt(OUTPUT, width = 4*numMemPorts)
  val mcReqStall    = UInt(INPUT, width = numMemPorts)
  // response
  val mcResValid    = UInt(INPUT, width = numMemPorts)
  val mcResCmd      = UInt(INPUT, width = 3*numMemPorts)
  val mcResSCmd     = UInt(INPUT, width = 4*numMemPorts)
  val mcResData     = UInt(INPUT, width = 64*numMemPorts)
  val mcResRtnCtl   = UInt(INPUT, width = rtnctl*numMemPorts)
  val mcResStall    = UInt(OUTPUT, width = numMemPorts)
  // flush
  val mcReqFlush    = UInt(OUTPUT, width = numMemPorts)
  val mcResFlushOK  = UInt(INPUT, width = numMemPorts)
  // control-status register interface
  val csrWrValid      = Bool(INPUT)
  val csrRdValid      = Bool(INPUT)
  val csrAddr         = UInt(INPUT, 16)
  val csrWrData       = UInt(INPUT, 64)
  val csrReadAck      = Bool(OUTPUT)
  val csrReadData     = UInt(OUTPUT, 64)
  // misc -- IDs for each AE
  val aeid            = UInt(INPUT, 4)

  override def clone = {
    new ConveyPersonalityVerilogIF(numMemPorts, rtnctl).asInstanceOf[this.type] }

  // rename signals to remain compatible with Verilog template
  def renameSignals() {
    dispInstValid.setName("disp_inst_vld")
    dispInstData.setName("disp_inst")
    dispRegID.setName("disp_aeg_idx")
    dispRegRead.setName("disp_aeg_rd")
    dispRegWrite.setName("disp_aeg_wr")
    dispRegWrData.setName("disp_aeg_wr_data")
    dispAegCnt.setName("disp_aeg_cnt")
    dispException.setName("disp_exception")
    dispIdle.setName("disp_idle")
    dispRtnValid.setName("disp_rtn_data_vld")
    dispRtnData.setName("disp_rtn_data")
    dispStall.setName("disp_stall")
    mcReqValid.setName("mc_rq_vld")
    mcReqRtnCtl.setName("mc_rq_rtnctl")
    mcReqData.setName("mc_rq_data")
    mcReqAddr.setName("mc_rq_vadr")
    mcReqSize.setName("mc_rq_size")
    mcReqCmd.setName("mc_rq_cmd")
    mcReqSCmd.setName("mc_rq_scmd")
    mcReqStall.setName("mc_rq_stall")
    mcResValid.setName("mc_rs_vld")
    mcResCmd.setName("mc_rs_cmd")
    mcResSCmd.setName("mc_rs_scmd")
    mcResData.setName("mc_rs_data")
    mcResRtnCtl.setName("mc_rs_rtnctl")
    mcResStall.setName("mc_rs_stall")
    mcReqFlush.setName("mc_rq_flush")
    mcResFlushOK.setName("mc_rs_flush_cmplt")
    csrWrValid.setName("csr_wr_vld")
    csrRdValid.setName("csr_rd_vld")
    csrAddr.setName("csr_address")
    csrWrData.setName("csr_wr_data")
    csrReadAck.setName("csr_rd_ack")
    csrReadData.setName("csr_rd_data")
    aeid.setName("i_aeid")
  }
}


/*
memory req-rsp adapters for Convey -- to be re-enabled and tested

// TODO update adapter to also work for writes
class ConveyMemReqAdp(p: MemReqParams) extends Module {
  val io = new Bundle {
    val genericReqIn = Decoupled(new GenericMemoryRequest(p)).flip
    val conveyReqOut = Decoupled(new MemRequest(32, 48, 64))
  }

  io.conveyReqOut.valid := io.genericReqIn.valid
  io.genericReqIn.ready := io.conveyReqOut.ready

  io.conveyReqOut.bits.rtnCtl := io.genericReqIn.bits.channelID
  io.conveyReqOut.bits.writeData := UInt(0)
  io.conveyReqOut.bits.addr := io.genericReqIn.bits.addr
  io.conveyReqOut.bits.size := UInt( log2Up(p.dataWidth/8) )
  io.conveyReqOut.bits.scmd := UInt(0)

  if(p.dataWidth != 64) {
    println("ConveyMemReqAdp requires p.dataWidth=64")
  } else {
    if (p.beatsPerBurst == 8) {
      io.conveyReqOut.bits.cmd := UInt(7)
    } else if (p.beatsPerBurst == 1) {
      io.conveyReqOut.bits.cmd := UInt(1)
    } else {
      println("Unsupported number of burst beats!")
    }
  }
}

class ConveyMemRspAdp(p: MemReqParams) extends Module {
  val io = new Bundle {
    val conveyRspIn = Decoupled(new MemResponse(32, 64)).flip
    val genericRspOut = Decoupled(new GenericMemoryResponse(p))
  }

  io.conveyRspIn.ready := io.genericRspOut.ready
  io.genericRspOut.valid := io.conveyRspIn.valid

  io.genericRspOut.bits.channelID := io.conveyRspIn.bits.rtnCtl
  io.genericRspOut.bits.readData := io.conveyRspIn.bits.readData
  // TODO carry cmd and scmd, if needed
  io.genericRspOut.bits.metaData := UInt(0)
}

*/
