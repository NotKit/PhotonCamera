package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import java.nio.Buffer;

public class LuckyOperator extends GLOneScript {
    Point insize;
    public long out = 0;
    public LuckyOperator(Point size) {
        super(new Point(size.x/(64),size.y/(64)), null, new GLFormat(GLFormat.DataType.FLOAT_16,4), "LuckyOperator/luckyoperator", "LuckyOperator");
        insize = size;
    }

    @Override
    public void StartScript() {
        ScriptParams scriptParams = (ScriptParams)additionalParams;
        GLProg glProg = glOne.glProgram;
        GLTexture input1 = new GLTexture(insize,new GLFormat(GLFormat.DataType.UNSIGNED_16),scriptParams.input);
        glProg.setTexture("InputBuffer",input1);
        glProg.setVar("CfaPattern",scriptParams.parameters.cfaPattern);
        workingTexture = new GLTexture(input1.mSize.x/2,input1.mSize.y/2,new GLFormat(GLFormat.DataType.FLOAT_16),(Buffer) null);
        glProg.drawBlocks(input1);
        GLTexture luckyTex = new GLTexture(input1.mSize,workingTexture.mFormat,(Buffer) null);
        glProg.drawBlocks(luckyTex);
        GLUtils glUtils = new GLUtils(glOne.glProcessing);
        //WorkingTexture = glUtils.gaussdown( glUtils.gaussdown(luckyTex,8),8);
        workingTexture =  glUtils.gaussdown(luckyTex,64);
        glOne.glProgram.drawBlocks(workingTexture);
        glOne.glProcessing.drawBlocksToOutput();
        glOne.glProgram.close();
        glOne.glProcessing.close();
        output = glOne.glProcessing.mOutBuffer;
        for(int i =0; i<output.remaining();i++){
            out+=((long)output.get(i)) + 128;
        }
        workingTexture.close();
        Log.d("LuckyOperator","Result:"+out);
    }

    @Override
    public void Run() {
        Compile();
        startT();
        StartScript();
        endT();
    }
}
