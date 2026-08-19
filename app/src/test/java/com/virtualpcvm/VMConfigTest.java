package com.virtualpcvm;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class VMConfigTest {
    @Test public void defaultsAreValid() {
        VMConfig config = new VMConfig();
        config.validate();
        assertEquals("x86_64", config.architecture);
    }

    @Test public void invalidRamRejected() {
        VMConfig config = new VMConfig();
        config.ramMb = 64;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test public void invalidCpuRejected() {
        VMConfig config = new VMConfig();
        config.cpuCount = 0;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test public void unsupportedArchitectureRejected() {
        VMConfig config = new VMConfig();
        config.architecture = "arm64";
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test public void pathTraversalIdRejected() {
        assertEquals(false, VMStorage.isValidId("../escape"));
        assertEquals(false, VMStorage.isValidId("/absolute"));
    }
}
