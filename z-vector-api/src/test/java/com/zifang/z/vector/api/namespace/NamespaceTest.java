package com.zifang.z.vector.api.namespace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Namespace 多租户测试
 */
class NamespaceTest {

    private NamespaceManager manager;

    @BeforeEach
    void setUp() {
        manager = NamespaceManager.createDefault();
    }

    @Test
    void createNamespace() {
        Namespace ns = Namespace.builder("tenant-a")
            .description("Test namespace")
            .maxCollections(10)
            .maxPoints(1000)
            .grant("user-1", Namespace.Permission.READ, Namespace.Permission.WRITE)
            .build();

        manager.createNamespace(ns);

        assertTrue(manager.namespaceExists("tenant-a"));
        assertEquals(2, manager.getNamespaceCount()); // default + tenant-a
    }

    @Test
    void deleteNamespace() {
        Namespace ns = Namespace.builder("tenant-a").build();
        manager.createNamespace(ns);

        assertTrue(manager.deleteNamespace("tenant-a"));
        assertFalse(manager.namespaceExists("tenant-a"));
    }

    @Test
    void cannotDeleteDefaultNamespace() {
        assertThrows(IllegalStateException.class, () -> {
            manager.deleteNamespace("default");
        });
    }

    @Test
    void permissionCheck() {
        Namespace ns = Namespace.builder("tenant-a")
            .grant("user-1", Namespace.Permission.READ, Namespace.Permission.WRITE)
            .grant("admin-1", Namespace.Permission.ADMIN)
            .build();

        manager.createNamespace(ns);

        assertTrue(manager.checkPermission("tenant-a", "user-1", Namespace.Permission.READ));
        assertTrue(manager.checkPermission("tenant-a", "user-1", Namespace.Permission.WRITE));
        assertFalse(manager.checkPermission("tenant-a", "user-1", Namespace.Permission.ADMIN));

        assertTrue(manager.checkPermission("tenant-a", "admin-1", Namespace.Permission.ADMIN));
    }

    @Test
    void requirePermissionThrows() {
        Namespace ns = Namespace.builder("tenant-a")
            .grant("user-1", Namespace.Permission.READ)
            .build();

        manager.createNamespace(ns);

        assertThrows(SecurityException.class, () -> {
            manager.requirePermission("tenant-a", "user-1", Namespace.Permission.WRITE);
        });
    }

    @Test
    void collectionQuota() {
        Namespace ns = Namespace.builder("tenant-a")
            .maxCollections(2)
            .build();

        manager.createNamespace(ns);

        manager.addCollectionToNamespace("tenant-a", "col-1");
        manager.addCollectionToNamespace("tenant-a", "col-2");

        assertThrows(IllegalStateException.class, () -> {
            manager.addCollectionToNamespace("tenant-a", "col-3");
        });
    }

    @Test
    void collectionManagement() {
        Namespace ns = Namespace.builder("tenant-a").build();
        manager.createNamespace(ns);

        manager.addCollectionToNamespace("tenant-a", "col-1");
        manager.addCollectionToNamespace("tenant-a", "col-2");

        Set<String> collections = manager.getNamespaceCollections("tenant-a");
        assertEquals(2, collections.size());
        assertTrue(collections.contains("col-1"));
        assertTrue(collections.contains("col-2"));

        manager.removeCollectionFromNamespace("tenant-a", "col-1");
        collections = manager.getNamespaceCollections("tenant-a");
        assertEquals(1, collections.size());
        assertFalse(collections.contains("col-1"));
    }

    @Test
    void defaultNamespace() {
        Namespace defaultNs = manager.getNamespace("default");
        assertNotNull(defaultNs);
        assertEquals("default", defaultNs.getName());
    }

    @Test
    void listNamespaces() {
        manager.createNamespace(Namespace.builder("ns-1").build());
        manager.createNamespace(Namespace.builder("ns-2").build());

        Set<String> namespaces = new java.util.HashSet<>(manager.listNamespaces());
        assertEquals(3, namespaces.size()); // default + ns-1 + ns-2
        assertTrue(namespaces.contains("default"));
        assertTrue(namespaces.contains("ns-1"));
        assertTrue(namespaces.contains("ns-2"));
    }
}
