package tv.own.owntv.core.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.model.MediaType

class ContentAccessPolicyTest {
    private val safe = CategoryEntity(7L, 2L, MediaType.MOVIE, "Family", "family")
    private val adult = CategoryEntity(8L, 2L, MediaType.MOVIE, "Adults 18+", "adult")
    private val grownUp = ProfileEntity(1L, "Adult", 0)
    private val child = ProfileEntity(2L, "Child", 0, isKids = true)

    @Test
    fun `manual item and category hides are final access denials`() {
        assertFalse(allows(grownUp, safe, SectionCustomizations(hiddenItems = mapOf("item" to "Hidden"))))
        assertFalse(
            allows(
                grownUp,
                safe,
                SectionCustomizations(hiddenCategories = setOf(CustomizeKeys.category(safe))),
            ),
        )
    }

    @Test
    fun `kids content is denied when its category cannot be classified`() {
        assertFalse(allowsSnapshot(child, null, null))
        assertFalse(allowsSnapshot(child, 99L, null))
        assertTrue(allowsSnapshot(grownUp, null, null))
    }

    @Test
    fun `kids content allows safe category and rejects adult category`() {
        assertTrue(allows(child, safe, SectionCustomizations()))
        assertFalse(allows(child, adult, SectionCustomizations()))
    }

    @Test
    fun `kids content denies a category from another source`() {
        val categoryFromAnotherSource = safe.copy(sourceId = safe.sourceId + 1L)
        assertFalse(
            ContentAccessPolicy.allowsSnapshot(
                profile = child,
                itemKey = "item",
                categoryId = categoryFromAnotherSource.id,
                category = categoryFromAnotherSource,
                customizations = SectionCustomizations(),
                sourceId = safe.sourceId,
                allowedSourceIds = setOf(safe.sourceId),
            ),
        )
    }

    @Test
    fun `content from a source not linked to the profile is denied`() {
        assertFalse(
            ContentAccessPolicy.allowsSnapshot(
                profile = grownUp,
                itemKey = "item",
                categoryId = safe.id,
                category = safe,
                customizations = SectionCustomizations(),
                sourceId = 99L,
                allowedSourceIds = setOf(safe.sourceId),
            ),
        )
    }

    @Test
    fun `episodes authorize through the series section`() {
        assertTrue(ContentAccessPolicy.sectionFor(MediaType.EPISODE) == MediaType.SERIES)
        assertTrue(ContentAccessPolicy.sectionFor(MediaType.MOVIE) == MediaType.MOVIE)
    }

    private fun allows(
        profile: ProfileEntity,
        category: CategoryEntity,
        customizations: SectionCustomizations,
    ) = ContentAccessPolicy.allowsSnapshot(
        profile = profile,
        itemKey = "item",
        categoryId = category.id,
        category = category,
        customizations = customizations,
        sourceId = category.sourceId,
        allowedSourceIds = setOf(category.sourceId),
    )

    private fun allowsSnapshot(
        profile: ProfileEntity,
        categoryId: Long?,
        category: CategoryEntity?,
    ) = ContentAccessPolicy.allowsSnapshot(
        profile = profile,
        itemKey = "item",
        categoryId = categoryId,
        category = category,
        customizations = SectionCustomizations(),
        sourceId = safe.sourceId,
        allowedSourceIds = setOf(safe.sourceId),
    )
}
