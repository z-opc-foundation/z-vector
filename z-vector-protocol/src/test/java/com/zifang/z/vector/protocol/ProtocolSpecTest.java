package com.zifang.z.vector.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 协议规范单元测试。
 */
class ProtocolSpecTest {

    @Test
    void versionExists() {
        assertNotNull(ProtocolSpec.VERSION);
        assertEquals("1.0.0", ProtocolSpec.VERSION);
    }

    @Test
    void milvusCompatibilityVersion() {
        assertNotNull(ProtocolSpec.MILVUS_VERSION);
    }

    @Test
    void qdrantCompatibilityVersion() {
        assertNotNull(ProtocolSpec.QDRANT_VERSION);
    }
}