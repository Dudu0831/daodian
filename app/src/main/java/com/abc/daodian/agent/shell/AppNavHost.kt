package com.abc.daodian.agent.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.abc.daodian.agent.conversation.ChatScreen
import com.abc.daodian.agent.conversation.ChatViewModel
import com.abc.daodian.agent.feature.FeatureRegistry
import com.abc.daodian.agent.memory.presentation.MemoryScreen
import com.abc.daodian.shared.navigation.Launch
import com.abc.daodian.shared.theme.DaodianColors
import kotlinx.coroutines.launch

/** 壳自己的几页。模块的页面由各自的 [FeatureUi.routes] 注册 */
object ShellRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDER = "provider"
    /** 记忆管理页（§6.9）。设置首页「记忆」那一行、对话里「记住了」的痕都到这里 */
    const val MEMORY = "memory"
    /** 权限与监听：体检结论、各模块要的系统权限、通知监听。只从设置首页「权限与监听」那一行进，模块页不往这里跳 */
    const val PERMISSIONS = "permissions"
}

/** 切页的淡入 / 淡出。库默认是两页互相淡变 700ms，每一页都显得慢（§8.1） */
private const val PAGE_FADE_MS = 180

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

    // 切页那一小段两页同时在屏幕上：往里走时新页的空白处不拦点击，会点穿到底下的旧页（点完「提醒」
    // 马上点「记账」的位置，就又跳一次）；往回退时要退掉的页还在上面，返回键再点一下就连退两层。
    // 库在动画走完才把当前页推到 RESUMED —— 没到之前盖一层，点击全吞掉（§8.1）
    val entry by navController.currentBackStackEntryAsState()
    val moving = entry?.let { it.lifecycle.currentStateAsState().value != Lifecycle.State.RESUMED } ?: false

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = ShellRoutes.CHAT,
            // 往里走：新页盖在旧页上淡入，旧页原样留到淡完；往回退：要退掉的页淡出，底下那页原样露出来
            enterTransition = { fadeIn(tween(PAGE_FADE_MS)) },
            exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { fadeOut(tween(PAGE_FADE_MS)) }
        ) {

            composable(ShellRoutes.CHAT) {
                // 左边的抽屉：各模块一张纸、设置在最底下（设计稿方向 B「两张纸」）
                val colors = DaodianColors.current
                val context = LocalContext.current
                // 不用 rememberDrawerState（它会随这一页存下来）：从抽屉去别处，对话页离开屏幕就丢掉，回来是新的、合着的。
                // 以前是跳转后马上把抽屉合上 —— 新页还没淡进来那几帧，露出底下合了抽屉的对话页，闪一下（§8.1）
                val drawerState = remember { DrawerState(DrawerValue.Closed) }
                val scope = rememberCoroutineScope()
                // 体检结论每次拉开抽屉重查一次：从系统设置回来，那一行要跟着变
                val healthMissing = remember(drawerState.isOpen) { FeatureRegistry.health(context).count { it.ok == false } }
                BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

                /** 从抽屉去别处：抽屉开着一起被新页盖住，不去动它 */
                fun leaveTo(route: String) = navController.navigate(route)

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

            composable(ShellRoutes.MEMORY) {
                MemoryScreen(onBack = { navController.popBackStack() })
            }

            composable(ShellRoutes.PERMISSIONS) {
                PermissionsScreen(uis = uis, onBack = { navController.popBackStack() }, open = { navController.navigate(it) })
            }

            uis.forEach { ui -> with(ui) { routes(nav) } }
        }

        if (moving) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                }
            )
        }
    }
}
