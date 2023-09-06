package com.metalbear.mirrord.products.rubymine

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import org.jetbrains.plugins.ruby.ruby.run.RunEnvironmentProvider

class RubyMineRunEnvironmentProvider: RunEnvironmentProvider() {
    override fun addEnvironment(p0: Sdk, p1: Module, p2: MutableMap<String, String>) {
        p2.put("DYLD_INSERT_LIBRARIES", "/Users/tal/Documents/projects/mirrord/target/universal-apple-darwin/debug/libmirrord_layer.dylib")
    }
}