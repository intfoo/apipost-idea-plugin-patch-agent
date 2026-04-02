package com.github.intfoo.agent;

import java.lang.instrument.Instrumentation;

public class AgentMain {
    public static void premain(String args, Instrumentation inst) {
        System.out.println("[ReadActionPatch] Agent loaded");
        inst.addTransformer(new ReadActionTransformer(), false);
    }
}