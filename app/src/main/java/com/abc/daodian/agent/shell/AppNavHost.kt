package com.abc.daodian.agent.shell

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.abc.daodian.agent.conversation.ChatScreen
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.shared.navigation.Launch
import com.abc.daodian.shared.theme.DaodianColors
import kotlinx.coroutines.launch

/** 壳自己的几页。模块的页面由各自的 [FeatureUi.routes] 注册 */
object ShellRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDER = "provider"
}

/**
 * 整个 app 的导航。对话页是起点；模块的页面遍历 [FeatureUi] 注册；
 * 从 app 外面带进来的去处（[Launch.Request]：小组件、通知、桌面速记）在这里分发。
 *
 * [vm] 在 NavHost 外面按 Activity 拿一次往下传 —— 不让每个目的地各自 viewModel()，
 * 避免 Navigation-Compose 按 backstack entry 分别建实例，导致数据不同步。模块页面用 activityViewModel() 同理。
 */
@Composable
fun AppNavHost(
    vm: ChatViewModel,
    navController: NavHostController = rememberNavController(),
    request: Launch.Request? = null,
    onRequestHandled: () -> Unit = {}
) {
    val uis = remember { FeatureRegistry.features.filterIsInstance<FeatureUi>() }

    fun toChat() = navController.navigate(ShellRoutes.CHAT) {
        popUpTo(ShellRoutes.CHAT) { inclusive = true }
        launchSingleTop = true
    }

    val nav = remember(navController) {
        object : AppNav {
            override fun open(route: String) = navController.navigate(route)
            override fun back() {
                navController.popBackStack()
            }
            override fun chat(prefill: String?) {
                prefill?.let(vm::prefill)
                navController.navigate(ShellRoutes.CHAT) {
                    popUpTo(ShellRoutes.CHAT) { inclusive = false }
                    launchSingleTop = true
                }
            }
            override fun trigger(key: String) {
                chat()
                vm.startTrigger(key)
            }
        }
    }

    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        val route = r.route
        if (route == null || route == ShellRoutes.CHAT || r.trigger != null || r.say != null) toChat()
        else navController.navigate(route) { launchSingleTop = true }
        // 每晚对账通知上点「现在」：回对话页，开一轮对账
        r.trigger?.let(vm::startTrigger)
        // 桌面速记交过来的一句话：回对话页，照常发出去（正忙就先放进输入框）
        r.say?.let { if (vm.aiBusy) vm.prefill(it) else vm.sendMessage(it) }
        onRequestHandled()
    }

    NavHost(navController = navController, startDestination = ShellRoutes.CHAT) {

        composable(ShellRoutes.CHAT) {
            // 左边的抽屉：各模块一张纸、设置在最底下（设计稿方向 B「两张纸」）
            val colors = DaodianColors.current
            val context = LocalContext.current
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            // 体检结论每次拉开抽屉重查一次：从系统设置回来，那一行要跟着变
            val healthMissing = remember(drawerState.isOpen) { FeatureRegistry.health(context).count { it.ok == false } }
            BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

            /** 从抽屉去别处：先把抽屉合上（瞬间），回来时不会还开着 */
            fun leaveTo(route: String) {
                navController.navigate(route)
                scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = colors.ink.copy(alpha = 0.34f),
                drawerContent = {
                    AppDrawer(
                        uis = uis,
                        healthMissing = healthMissing,
                        onClose = { scope.launch { drawerState.close() } },
                        open = ::leaveTo,
                        onOpenSettings = { leaveTo(ShellRoutes.SETTINGS) }
                    )
                }
            ) {
                ChatScreen(
                    vm = vm,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenProvider = { navController.navigate(ShellRoutes.PROVIDER) },
                    onOpen = { navController.navigate(it) }
                )
            }
        }

        composable(ShellRoutes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                uis = uis,
                onBack = { navController.popBackStack() },
                onOpenProvider = { navController.navigate(ShellRoutes.PROVIDER) },
                open = { navController.navigate(it) }
            )
        }

        composable(ShellRoutes.PROVIDER) {
            ProviderScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        uis.forEach { ui -> with(ui) { routes(nav) } }
    }
}
