package org.fentanylsolutions.boppatches.core;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("BOP Patches Core")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({ "org.fentanylsolutions.boppatches.core." })
public class BopPatchesLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        BopJarRuntimePatcher.patchIfNecessary(data);
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
