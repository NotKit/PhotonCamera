package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class DebugAWB extends Node {
    public DebugAWB(String rid, String name) {
        super(rid, name);
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        GLProg glProg = basePipeline.glint.glProgram;
        glProg.useAssetProgram("DebugAWB/applyvector");
        glProg.setVar("colorvec", 0.5f,1.0f,0.3f);
        glProg.setTexture("InputBuffer", previousNode.workingTexture);
        workingTexture = new GLTexture(previousNode.workingTexture);
        glProg.drawBlocks(workingTexture);
        glProg.close();
    }
}
