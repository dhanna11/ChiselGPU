// 
// Copyright 2015 Jeff Bush
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//     http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

import Chisel._

//
// Bus arbiter. Performs the following functions:
// - Serializes requests from multiple masters for the external system
//   memory bus.
// - Hides external bus protocol behind a simpler request/response
//   interface.
//
// Caveats:
// - Does not attempt to perform caching or read/write combining
// - Will not consolidate requests for the same address
// It is expected that individual masters will take care of this
// Usually these don't end up being optimized cases. If they are, the
// interfaces can optimized more efficiently for their own use pattern.
//

class ArbiterReadPort(burstByteCount : Int) extends Bundle {
	val request = Bool(INPUT)
	val ack = Bool(OUTPUT) 
	val address = UInt(INPUT, 32)
	val data = UInt(OUTPUT, burstByteCount * 8)
	
	override def clone = new ArbiterReadPort(burstByteCount).asInstanceOf[this.type]
}

class ArbiterWritePort(burstByteCount : Int) extends Bundle {
	val request = Bool(INPUT)
	val ack = Bool(OUTPUT) 
	val address = UInt(INPUT, 32)
	val data = UInt(INPUT, burstByteCount * 8)

	override def clone = new ArbiterWritePort(burstByteCount).asInstanceOf[this.type]
}

class BusArbiter(numReadPorts : Int, numWritePorts : Int, axiDataWidthBits : Int, 
	burstByteCount : Int) extends Module {

	val io = new Bundle {
		val readPorts = Vec.fill(numReadPorts)(new ArbiterReadPort(burstByteCount))
		val writePorts = Vec.fill(numWritePorts)(new ArbiterWritePort(burstByteCount))
		val axiBus = new Axi4Master(axiDataWidthBits)
	}

	val burstTransferCount = burstByteCount / (axiDataWidthBits / 8)

	def convertToVec(bitArray : UInt, bitsPerElement : Int) = Vec.tabulate(bitArray.getWidth() /
		bitsPerElement)((i : Int) => bitArray((i + 1) * bitsPerElement - 1, i * bitsPerElement))

	//
	// Read Logic
	//
	val s_read_idle :: s_send_read_addr :: s_read_burst_active :: s_read_burst_complete :: Nil = Enum(UInt(), 4)
	val readState = Reg(init = s_read_idle)
	val readDataReg = Reg(UInt(width = burstByteCount * 8))
	val activeReader = Reg(UInt(width = log2Up(numReadPorts)))
	val readBurstCount = Reg(UInt(width = log2Up(burstTransferCount)))

	var i = 0
	for (i <- 0 until numReadPorts ) {
		io.readPorts(i).data := readDataReg
		io.readPorts(i).ack := (readState === s_read_burst_complete) && 
			(activeReader === UInt(i))
	}

	io.axiBus.arvalid := readState === s_send_read_addr
	io.axiBus.araddr := io.readPorts(activeReader).address
	io.axiBus.arlen := UInt(burstTransferCount - 1)
	io.axiBus.arsize := UInt(log2Down(burstByteCount))
	io.axiBus.rready := readState === s_read_burst_active
	val readLanes = convertToVec(readDataReg, axiDataWidthBits)
	val readArbiter = Module(new Arbiter(numReadPorts))
	var readRequestBitmap = UInt(io.readPorts(0).request && readState === s_read_idle)
	for (i <- 1 until numReadPorts)
		readRequestBitmap = Cat(io.readPorts(i).request && readState === s_read_idle, readRequestBitmap)
	
	readArbiter.io.request := readRequestBitmap
	readArbiter.io.enableUpdate := Bool(true)

	switch (readState) {
		is (s_read_idle) {
			when (readArbiter.io.grantOneHot != UInt(0)) {
				// Choose a master to begin a read transaction
				activeReader := OHToUInt(readArbiter.io.grantOneHot)
				readState := s_send_read_addr
			}
		}
		
		is (s_send_read_addr) {
			when (io.axiBus.arvalid) {
				readState := s_read_burst_active
				readBurstCount := UInt(0)
			}
		}

		is (s_read_burst_active) {
			when (io.axiBus.rvalid) {
				readLanes(~readBurstCount) := io.axiBus.rdata
				readBurstCount := readBurstCount + UInt(1)
				when (readBurstCount === UInt(burstTransferCount - 2)) {
					readState := s_read_burst_complete
				}
			}
		}

		is (s_read_burst_complete) {
			readState := s_read_idle
		}
	}

	//
	// Write Logic
	//
	val s_write_idle :: s_send_write_addr :: s_write_burst_active :: s_write_burst_complete :: Nil = Enum(UInt(), 4)
	val writeState = Reg(init = s_write_idle)
	val activeWriter = Reg(UInt(width = log2Up(numWritePorts)))
	val writeBurstCount = Reg(UInt(width = log2Up(burstTransferCount)))
	for (i <- 0 until numWritePorts ) {
		io.writePorts(i).ack := (writeState === s_write_burst_complete) && 
			(activeWriter === UInt(i))
	}

	io.axiBus.awvalid := writeState === s_send_write_addr
	io.axiBus.awaddr := io.writePorts(activeWriter).address
	io.axiBus.awlen := UInt(burstTransferCount - 1)
	io.axiBus.awsize := UInt(log2Down(burstByteCount))
	io.axiBus.wvalid := writeState === s_write_burst_active

	// Select the appropriate write data
	val writeData = io.writePorts(activeWriter).data
	val writeLanes = convertToVec(writeData, axiDataWidthBits)
	io.axiBus.wdata := writeLanes(~writeBurstCount)
	io.axiBus.wlast := (writeBurstCount === UInt(burstTransferCount - 1)) && 
		(writeState === s_write_burst_active)
	io.axiBus.bready := writeState === s_write_burst_complete
	
	val writeArbiter = Module(new Arbiter(numWritePorts))
	var writeRequestBitmap = UInt(io.writePorts(0).request && writeState === s_write_idle)
	for (i <- 1 until numWritePorts)
		writeRequestBitmap = Cat(io.writePorts(i).request && writeState === s_write_idle, writeRequestBitmap)

	writeArbiter.io.request := writeRequestBitmap
	writeArbiter.io.enableUpdate := Bool(true)

	switch (writeState) {
		is (s_write_idle) {
			when (writeArbiter.io.grantOneHot != UInt(0)) {
				// Choose a master to begin a write transaction
				activeWriter := OHToUInt(writeArbiter.io.grantOneHot)
				writeState := s_send_write_addr
			}
		}
		
		is (s_send_write_addr) {
			when (io.axiBus.awvalid) {
				writeState := s_write_burst_active
				writeBurstCount := UInt(0)
			}
		}
		
		is (s_write_burst_active) {
			when (io.axiBus.wready) {
				writeBurstCount := writeBurstCount + UInt(1)
				when (writeBurstCount === UInt(burstTransferCount - 1)) {
					writeState := s_read_burst_complete
				}
			}
		}

		is (s_write_burst_complete) {
			when (io.axiBus.bvalid) {
				writeState := s_write_idle
			}
		}
	}
}





