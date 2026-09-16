package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class ChromaticFlow extends Node {

    public ChromaticFlow() {
        super("", "ChromaticFlow");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {

        glProg.useAssetProgram("ChromaticFlow/chromaticgrad");
        glProg.setTexture("InputBuffer",previousNode.workingTexture);
        glProg.drawBlocks(basePipeline.getMain3());

        glProg.setDefine("SIZE",previousNode.workingTexture.mSize);
        glProg.useAssetProgram("ChromaticFlow/chromaticcomp");
        glProg.setTexture("DiffBuffer",basePipeline.getMain3());
        glProg.setTexture("InputBuffer",previousNode.workingTexture);
        workingTexture = basePipeline.getMain();
        glProg.drawBlocks(workingTexture);
        glProg.closed = true;
    }
}
