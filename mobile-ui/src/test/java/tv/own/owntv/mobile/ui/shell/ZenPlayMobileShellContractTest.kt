package tv.own.owntv.mobile.ui.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenPlayMobileShellContractTest {
    private val source = File("src/main/java/tv/own/owntv/mobile/ui/shell/MobileShell.kt").readText()

    @Test
    fun notificationTapCannotStackASecondPlayerDestination() {
        val request = source.substringAfter("LaunchedEffect(openPlayerRequested)")
            .substringBefore("// \"Start on\"")

        assertTrue(request.contains("currentRoute != PLAYER_ROUTE"))
        assertTrue(request.contains("launchSingleTop = true"))
    }

    @Test
    fun topBarKeepsOnlyRefreshAndSearchBesideResponsiveZenPlayWordmark() {
        val topBar = source.substringAfter("TopAppBar(").substringBefore("bottomBar =")

        assertTrue(topBar.contains("contentDescription = homeDescription"))
        assertTrue(source.contains(".data(R.drawable.zenplay_wordmark_horizontal)"))
        assertTrue(topBar.contains("AsyncImage("))
        assertTrue(source.contains("ImageRequest.Builder(context)"))
        assertTrue(source.contains(".crossfade(false)"))
        assertFalse(topBar.contains("painterResource("))
        assertTrue(source.contains("windowWidthDp < 360 -> 96.dp"))
        assertTrue(source.contains("compactHandset -> 120.dp"))
        assertTrue(source.contains("useRail -> 156.dp"))
        assertTrue(topBar.contains("contentDescription = refreshDescription"))
        assertTrue(topBar.contains("imageVector = MobileIcons.Search"))
        assertFalse(topBar.contains("imageVector = MobileIcons.Settings"))
        assertFalse(topBar.contains("imageVector = MobileIcons.Person"))
        assertFalse(topBar.contains("MobileDestination.SETTINGS.route"))
        assertFalse(topBar.contains("navController.navigateToTab(MobileDestination.MORE)"))
        assertFalse(topBar.contains("R.string.zenplay_my_zenplay"))
        assertFalse(topBar.contains("R.string.profiles_title"))
    }

    @Test
    fun topBarStaysVisibleWhileEveryCatalogueScrolls() {
        val topBar = source.substringAfter("TopAppBar(").substringBefore("bottomBar =")

        assertFalse(source.contains("enterAlwaysScrollBehavior"))
        assertFalse(source.contains("nestedScroll(scrollBehavior.nestedScrollConnection)"))
        assertFalse(topBar.contains("scrollBehavior ="))
        assertTrue(source.contains("if (!fullscreen) Box("))
    }

    @Test
    fun fullScreenPlayerBypassesPanelGlassClippingAndNesting() {
        val pageModifier = source.substringAfter("val pageModifier = if (fullscreen)")
            .substringBefore("Box(pageModifier)")
        val fullscreenModifier = pageModifier.substringBefore("} else {")
        val browsingModifier = pageModifier.substringAfter("} else {")

        assertTrue(fullscreenModifier.contains("Modifier.fillMaxSize()"))
        assertFalse(fullscreenModifier.contains("glassSurface"))
        assertFalse(fullscreenModifier.contains(".clip("))
        assertTrue(browsingModifier.contains(".glassSurface(GlassSurface.PANELS, MobilePageShape)"))
        assertTrue(browsingModifier.contains(".clip(MobilePageShape)"))

        val host = source.substringAfter("val navigationHost: @Composable () -> Unit")
            .substringBefore("// Low over the page")
        val fullscreenHost = host.substringAfter("if (fullscreen) {").substringBefore("} else {")
        val browsingHost = host.substringAfter("} else {")

        assertTrue(fullscreenHost.contains("navigationHost()"))
        assertFalse(fullscreenHost.contains("GlassNest"))
        assertTrue(browsingHost.contains("GlassNest(GlassSurface.PANELS)"))
    }
}
