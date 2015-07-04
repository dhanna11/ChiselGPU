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
// Tile Buffer
// This stores rendered depth, stencil, and color information for a small
// square portion of the framebuffer (a tile).  It performs alpha blending,
// stencil checks, and depth checks. It has a three stage pipeline and 
// can accept one pixel per cycle without stalling. When rendering is finished
// for the tile, writing the reg_start_resolve register will cause this to write
// the contents of the color buffer to main memory via the arbiter.
//
// Constraints:
// - Assumes pre-multiplied alpha
// - 32 bpp output
// - The same pixel location cannot be written twice within 1 cycle
// - There must be 3 cycles after the last write before a resolve
//
// Open Questions/To do:
// - How to clear the framebuffer efficiently?  Do it during resolve?
// - Stencil and depth buffers are not implemented yet
// - Use advanced parameterization for tile size and burstByteCount
//   (https://chisel.eecs.berkeley.edu/2.2.0/chisel-parameters.pdf)
// - How does early-Z connect with this module?
// - Should this store and accept 2x2 quads instead of individual pixels?
//

class RGBAColor extends Bundle {
	val red = UInt(width = 8)
	val green = UInt(width = 8)
	val blue = UInt(width = 8)
	val alpha = UInt(width = 8)
}

class TileBuffer(tileSize : Int, burstByteCount : Int) extends Module {
	val tileCoordBits = log2Up(tileSize)
	val colorChannelBits = 8
	val depthBufferBits = 24
	val stencilBufferBits = 8
	val colorBufferBits = colorChannelBits * 4
	val bytesPerPixel = 4

	val io = new Bundle {
		// Pixel update interface
		val pixelValid = Bool(INPUT)
		val pixelX = UInt(INPUT, tileCoordBits)
		val pixelY = UInt(INPUT, tileCoordBits)
		val pixelColor = (new RGBAColor).asInput
		
		val resolveArbPort = new ArbiterWritePort(burstByteCount).flip
	
		// Resolve interface. Copy color data back to memory.
		val registerUpdate = new RegisterUpdate().flip
	}
	
	// Handle configuration updates from the command processor
	val resolveBaseAddress = Reg(UInt(INPUT, 32))
	val resolveStride = Reg(UInt(INPUT, 32))
	val enableAlpha = Reg(Bool(), init=Bool(false))

	when (io.registerUpdate.update) {
		switch (io.registerUpdate.address) {
			is (regids.reg_resolve_base_addr) {
				resolveBaseAddress := io.registerUpdate.value
			}

			is (regids.reg_resolve_stride) {
				resolveStride := io.registerUpdate.value
			}

			is (regids.reg_enable_alpha) {
				enableAlpha := io.registerUpdate.value(0)
			}
		}
	}

	val colorMemory = Mem(new RGBAColor, tileSize * tileSize, seqRead=true)

	val s_resolve_not_active :: s_resolve_collect_first :: s_resolve_collect_rest :: s_resolve_writeback :: Nil = Enum(UInt(), 4)
	val resolveStateReg = Reg(init=s_resolve_not_active)
	val resolveInternalAddrReg = Reg(UInt(width = tileCoordBits * 2))

	// 
	// Stage 1: read existing color at location
	//
	val pixelAddressIn = Cat(io.pixelY, io.pixelX)
	val pixelAddressStage1Reg = Reg(UInt(width = tileCoordBits * 2))
	val pixelValidStage1Reg = Reg(Bool())
	val newPixelColorStage1Reg = Reg(new RGBAColor)
	val readColorValue = new RGBAColor
	val readAddressReg = Reg(UInt(width = tileCoordBits * 2))
	
	when (resolveStateReg === s_resolve_collect_first || resolveStateReg === s_resolve_collect_rest) {
		// When in resolve mode, we use this read port to get the pixel
		// values
		readAddressReg := resolveInternalAddrReg
	}
	.elsewhen (io.pixelValid) {
		readAddressReg := pixelAddressIn
	}

	readColorValue := colorMemory(readAddressReg)
	val oldPixelColorStage1 = readColorValue
	pixelValidStage1Reg := io.pixelValid;
	pixelAddressStage1Reg := pixelAddressIn
	newPixelColorStage1Reg := io.pixelColor

	//
	// Stage 2:
	// - XXX Perform visibility checks (stencil, depth)
	// - Multiply destination pixel by one minus alpha
	//
	val newPixelColorStage2Reg = Reg(new RGBAColor)
	val pixelAddressStage2Reg = Reg(UInt(width = tileCoordBits * 2))
	val updatePixelStage2Reg = Reg(Bool())
	val oldWeightedColorStage2Reg = Reg(new RGBAColor)

	pixelAddressStage2Reg := pixelAddressStage1Reg
	val oneMinusAlpha = UInt(0xff) - newPixelColorStage1Reg.alpha
	oldWeightedColorStage2Reg.red := (oldPixelColorStage1.red * oneMinusAlpha)(15, 8)
	oldWeightedColorStage2Reg.green := (oldPixelColorStage1.green * oneMinusAlpha)(15, 8)
	oldWeightedColorStage2Reg.blue := (oldPixelColorStage1.blue * oneMinusAlpha)(15, 8)
	oldWeightedColorStage2Reg.alpha := (oldPixelColorStage1.alpha * oneMinusAlpha)(15, 8)
	newPixelColorStage2Reg := newPixelColorStage1Reg

	// XXX perform depth and stencil checks here
	updatePixelStage2Reg := pixelValidStage1Reg
	
	//
	// Stage 3:
	// - Blend pixel values, clamp, and writeback
	// - Write new pixel value (if visible)
	//
	def clamp = (x : UInt) => Mux(x < UInt(255), x, UInt(255))
	
	val blendedColor = new RGBAColor
	blendedColor.red := clamp(oldWeightedColorStage2Reg.red + newPixelColorStage2Reg.red)
	blendedColor.blue := clamp(oldWeightedColorStage2Reg.blue + newPixelColorStage2Reg.blue)
	blendedColor.green := clamp(oldWeightedColorStage2Reg.green + newPixelColorStage2Reg.green)
	blendedColor.alpha := clamp(oldWeightedColorStage2Reg.alpha + newPixelColorStage2Reg.alpha)
	
	val writebackColor = Mux(enableAlpha, blendedColor, newPixelColorStage2Reg)
	when (updatePixelStage2Reg) {
		colorMemory(pixelAddressStage2Reg) := writebackColor
	}

	// 
	// Resolve logic. The read port on internal color buffer SRAM is 32 bits, but
	// the external bus interface is wider. Read individual words, collect into
	// a larger register, then push this to the bus interface.
	//
	val numLanes = burstByteCount / (colorBufferBits / 8)
	val resolveLaneReg = Reg(UInt(width = log2Up(numLanes)))
	val writeDataReg = Reg(UInt(width = burstByteCount * 8))
	val resolveWriteAddressReg = Reg(UInt(width = 32))
	io.resolveArbPort.request := resolveStateReg === s_resolve_writeback
	io.resolveArbPort.address := resolveWriteAddressReg

	def writeDataVec = Vec.tabulate(writeDataReg.getWidth() /
		colorBufferBits)((i : Int) => writeDataReg((i + 1) * colorBufferBits - 1, i * colorBufferBits))
	
	switch (resolveStateReg) {
		is (s_resolve_not_active) {
			when (io.registerUpdate.update && io.registerUpdate.address === regids.reg_start_resolve) {
				resolveStateReg := s_resolve_collect_first
				resolveLaneReg := UInt(0)
				resolveWriteAddressReg := resolveBaseAddress
				resolveInternalAddrReg := UInt(0)
			}
		}

		is (s_resolve_collect_first) {
			resolveStateReg := s_resolve_collect_rest
			resolveInternalAddrReg := resolveInternalAddrReg + UInt(1)
		}

		is (s_resolve_collect_rest) {
			resolveLaneReg := resolveLaneReg + UInt(1)
			when (resolveLaneReg === UInt(numLanes - 1)) {
				resolveStateReg := s_resolve_writeback
			}
			.otherwise {
				resolveInternalAddrReg := resolveInternalAddrReg + UInt(1)
			}

			writeDataVec(~resolveLaneReg) := readColorValue.toBits
		}
		
		is (s_resolve_writeback) {
			// Write data to the bus arbiter
			when (io.resolveArbPort.ready) {
				when (resolveInternalAddrReg === UInt(tileSize * tileSize)) {
					resolveStateReg := s_resolve_not_active
				}
				.otherwise {
					resolveStateReg := s_resolve_collect_first
					resolveLaneReg := UInt(0)
				}

				// Increment write address
				when (resolveInternalAddrReg(tileCoordBits - 1, 0) === UInt(tileSize * bytesPerPixel - burstByteCount)) {
					// End of row, skip ahead by stride
					resolveWriteAddressReg := resolveWriteAddressReg + UInt(burstByteCount) + 
						resolveStride
				}
				.otherwise {
					// Next burst in same row
					resolveWriteAddressReg := resolveWriteAddressReg + UInt(burstByteCount)
				}
			}
		}
	}
	
	io.resolveArbPort.data := writeDataReg
}



