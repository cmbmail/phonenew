package com.phonecost.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataScopeTest {

    @Nested
    @DisplayName("allScope")
    class AllScopeTests {

        @Test
        @DisplayName("ADMIN/FINANCE scope: isAllScope=true, any org visible")
        void allScopeBasics() {
            DataScope scope = DataScope.allScope();
            assertTrue(scope.isAllScope());
            assertNull(scope.getPathPrefix());
            assertNull(scope.getSingleOrgId());
            assertNull(scope.getVisibleOrgIds());
        }

        @Test
        @DisplayName("Any orgId is visible in allScope")
        void anyOrgVisible() {
            DataScope scope = DataScope.allScope();
            assertTrue(scope.isOrgVisible(1L));
            assertTrue(scope.isOrgVisible(999L));
            assertTrue(scope.isOrgVisible(-1L));
        }

        @Test
        @DisplayName("filterByOrgId returns all items in allScope")
        void filterReturnsAll() {
            DataScope scope = DataScope.allScope();
            List<long[]> items = List.of(new long[]{1, 100}, new long[]{2, 200}, new long[]{3, 300});
            List<long[]> filtered = scope.filterByOrgId(items, arr -> arr[0]);
            assertEquals(3, filtered.size());
        }
    }

    @Nested
    @DisplayName("subtreeScope")
    class SubtreeScopeTests {

        @Test
        @DisplayName("BRANCH subtree: only listed orgIds visible")
        void subtreeBasics() {
            List<Long> visible = List.of(6L, 10L, 11L, 12L);
            DataScope scope = DataScope.subtreeScope("/5/6/", visible);

            assertFalse(scope.isAllScope());
            assertEquals("/5/6/", scope.getPathPrefix());
            assertNull(scope.getSingleOrgId());
            assertEquals(visible, scope.getVisibleOrgIds());
        }

        @Test
        @DisplayName("Only listed orgIds are visible")
        void orgVisibility() {
            List<Long> visible = List.of(6L, 10L, 11L);
            DataScope scope = DataScope.subtreeScope("/5/6/", visible);

            assertTrue(scope.isOrgVisible(6L));
            assertTrue(scope.isOrgVisible(10L));
            assertFalse(scope.isOrgVisible(99L));
        }

        @Test
        @DisplayName("null orgId is not visible in subtree scope")
        void nullOrgNotVisible() {
            DataScope scope = DataScope.subtreeScope("/5/6/", List.of(6L));
            assertFalse(scope.isOrgVisible(null));
        }
    }

    @Nested
    @DisplayName("singleOrgScope")
    class SingleOrgScopeTests {

        @Test
        @DisplayName("DEPARTMENT scope: only own org visible")
        void singleOrgBasics() {
            DataScope scope = DataScope.singleOrgScope(42L);

            assertFalse(scope.isAllScope());
            assertNull(scope.getPathPrefix());
            assertEquals(42L, scope.getSingleOrgId());
            assertEquals(List.of(42L), scope.getVisibleOrgIds());
        }

        @Test
        @DisplayName("Only the single org is visible")
        void orgVisibility() {
            DataScope scope = DataScope.singleOrgScope(42L);

            assertTrue(scope.isOrgVisible(42L));
            assertFalse(scope.isOrgVisible(43L));
        }
    }

    @Nested
    @DisplayName("filterByOrgId")
    class FilterTests {

        @Test
        @DisplayName("Unassigned items (orgId=-1) always visible")
        void unassignedAlwaysVisible() {
            DataScope scope = DataScope.singleOrgScope(42L);

            record Item(Long orgId, String name) {}
            List<Item> items = List.of(
                new Item(42L, "my-dept"),
                new Item(-1L, "unassigned"),
                new Item(99L, "other-dept")
            );

            List<Item> filtered = scope.filterByOrgId(items, Item::orgId);
            assertEquals(2, filtered.size());
            assertTrue(filtered.stream().anyMatch(i -> i.name().equals("unassigned")));
            assertTrue(filtered.stream().anyMatch(i -> i.name().equals("my-dept")));
        }

        @Test
        @DisplayName("Subtree scope filters correctly")
        void subtreeFilter() {
            List<Long> visible = List.of(6L, 10L);
            DataScope scope = DataScope.subtreeScope("/5/6/", visible);

            record Item(Long orgId, String name) {}
            List<Item> items = List.of(
                new Item(6L, "branch"),
                new Item(10L, "dept1"),
                new Item(11L, "dept2-not-visible"),
                new Item(-1L, "unassigned")
            );

            List<Item> filtered = scope.filterByOrgId(items, Item::orgId);
            assertEquals(3, filtered.size());
            assertFalse(filtered.stream().anyMatch(i -> i.name().equals("dept2-not-visible")));
        }

        @Test
        @DisplayName("Empty scope (-999) filters out everything except unassigned")
        void emptyScope() {
            DataScope scope = DataScope.singleOrgScope(-999L);

            record Item(Long orgId, String name) {}
            List<Item> items = List.of(
                new Item(1L, "org1"),
                new Item(-1L, "unassigned")
            );

            List<Item> filtered = scope.filterByOrgId(items, Item::orgId);
            assertEquals(1, filtered.size());
            assertEquals("unassigned", filtered.get(0).name());
        }
    }
}
