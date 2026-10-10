package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.render.*
import kotlin.test.*

class RenderingRegressionTest {
    private fun image(width: Int, height: Int, pixel: (Int, Int) -> List<Int>): PixelBuffer {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) pixel(x,y).forEachIndexed { c,v -> bytes[(y*width+x)*4+c] = v.toByte() }
        return PixelBuffer(width,height,bytes)
    }
    @Test fun orderedDitherPreservesQuarterAndThreeQuarterTone() {
        for ((value, white) in listOf(64 to 4, 128 to 8, 192 to 12)) {
            val out = OrderedDitherKernel.apply(image(4,4) { _,_ -> listOf(value,value,value,255) }, ParameterValue.Integer(2))
            assertEquals(white, (0 until 16).count { out.rgba[it*4].toInt() and 255 == 255 })
        }
    }
    @Test fun legacyDitherIsPinnedAndExplicitUpgradeChangesIt() {
        val input = image(4,4) { _,_ -> listOf(64,64,64,255) }
        val old = EffectInstance("d", "ordered_dither")
        val renderer = KotlinPipelineRenderer()
        val request = RenderRequest(1,1,input,listOf(old),RenderQuality.FINAL,0)
        assertEquals(0, renderer.render(request).output.rgba[0].toInt())
        val corrected = renderer.render(request.copy(effects=listOf(BuiltInEffects.OrderedDither.instantiate("d")))).output
        assertEquals(4, (0 until 16).count { corrected.rgba[it*4].toInt() and 255 == 255 })
    }
    @Test fun pixelateDoesNotBleedInvisibleColourIntoVisiblePixels() {
        val input = image(2,1) { x,_ -> if(x==0) listOf(255,0,0,255) else listOf(0,0,255,0) }
        val out = PixelateKernel.apply(input,ParameterValue.Integer(2))
        assertContentEquals(byteArrayOf(-1,0,0,-128,-1,0,0,-128),out.rgba)
        val legacy = PixelateKernel.apply(input,ParameterValue.Integer(2),associated=false)
        assertContentEquals(byteArrayOf(-128,0,-128,-128,-128,0,-128,-128),legacy.rgba)
    }
    @Test fun blurPreservesVisibleColourAndClampedBorders() {
        val input = image(2,1) { x,_ -> if(x==0) listOf(255,0,0,255) else listOf(0,0,255,0) }
        assertContentEquals(byteArrayOf(-1,0,0,170.toByte(),-1,0,0,85),BoxBlurKernel.apply(input,ParameterValue.Integer(1)).rgba)
        val one = image(1,1) { _,_ -> listOf(12,34,56,78) }
        assertContentEquals(one.rgba,BoxBlurKernel.apply(one,ParameterValue.Integer(64)).rgba)
    }
    @Test fun opacityInterpolatesAlphaContinuously() {
        val a=image(1,1){_,_->listOf(255,0,0,255)}
        val b=image(1,1){_,_->listOf(128,0,128,128)}
        val half=NormalOpacityBlend.apply(a,b,.5).rgba
        assertEquals(192,half[3].toInt() and 255)
        val nearly=NormalOpacityBlend.apply(a,b,.999).rgba
        assertEquals(128,nearly[3].toInt() and 255)
        assertContentEquals(b.rgba,NormalOpacityBlend.apply(a,b,1.0).rgba)
    }
    @Test fun spatialParametersUseDocumentPixels() {
        val full=image(8,1){x,_->listOf(if(x<2) 0 else if(x<4) 100 else 200,0,0,255)}
        val preview=image(4,1){x,_->listOf(if(x==0) 0 else if(x==1) 100 else 200,0,0,255)}
        val request=RenderRequest(1,1,preview,emptyList(),RenderQuality.PREVIEW,0,documentWidth=8,documentHeight=1)
        val reduced=PixelateKernel.apply(preview,ParameterValue.Integer(4),request)
        val final=PixelateKernel.apply(full,ParameterValue.Integer(4))
        for(x in 0..3) assertEquals(final.rgba[x*8],reduced.rgba[x*4])
    }
    @Test fun ditherUsesDocumentOriginAcrossRegions() {
        val input=image(8,4){_,_->listOf(128,128,128,255)}
        val whole=OrderedDitherKernel.apply(input,ParameterValue.Integer(2))
        val region=image(3,4){_,_->listOf(128,128,128,255)}
        val request=RenderRequest(1,1,region,emptyList(),RenderQuality.FINAL,0,originX=3)
        val tile=OrderedDitherKernel.apply(region,ParameterValue.Integer(2),request)
        for(y in 0..3) for(x in 0..2) assertEquals(whole.rgba[(y*8+x+3)*4],tile.rgba[(y*3+x)*4])
    }
    @Test fun cancellationAlsoAppliesToEmptyStacksAndCacheHits() {
        val input=image(2,2){_,_->listOf(0,0,0,255)}
        assertFailsWith<RenderCancelledException> {
            KotlinPipelineRenderer().render(RenderRequest(1,1,input,emptyList(),RenderQuality.PREVIEW,0,CancellationProbe{true}))
        }
    }
    @Test fun prefixCacheDoesNotChangeInputsOrConfuseUndoRevisions() {
        val input=image(2,1){_,_->listOf(10,50,100,255)}
        val original=input.rgba.copyOf()
        val renderer=KotlinPipelineRenderer(1024)
        val invert=BuiltInEffects.Invert.instantiate("i")
        val request=RenderRequest(1,4,input,listOf(invert),RenderQuality.PREVIEW,0)
        val first=renderer.render(request).output.rgba.copyOf()
        renderer.render(request.copy(effects=listOf(invert.copy(opacity=.5))))
        assertContentEquals(first,renderer.render(request.copy(generation=3)).output.rgba)
        assertContentEquals(original,input.rgba)
    }
}
