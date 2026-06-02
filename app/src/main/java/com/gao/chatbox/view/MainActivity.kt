package com.gao.chatbox.view

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import com.gao.chatbox.view.databinding.ActivityMainBinding
import com.gao.chatbox.view.ui.home.history.HistoryFragment
import com.gao.chatbox.view.ui.home.quickstart.QuickStartFragment
import com.gao.chatbox.view.ui.home.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"
    }

    private lateinit var binding: ActivityMainBinding

    private val quickStartFragment by lazy { QuickStartFragment() }
    private val historyFragment by lazy { HistoryFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Always start with clean fragment state to avoid duplicates after language change
        clearAllFragments()

        // Restore selected tab after configuration change
        val selectedTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_quick_start) ?: R.id.nav_quick_start
        val targetFragment = when (selectedTabId) {
            R.id.nav_history -> historyFragment
            R.id.nav_settings -> settingsFragment
            else -> quickStartFragment
        }
        binding.bottomNavigation.selectedItemId = selectedTabId
        switchFragment(targetFragment)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_quick_start -> {
                    switchFragment(quickStartFragment)
                    true
                }
                R.id.nav_history -> {
                    switchFragment(historyFragment)
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, binding.bottomNavigation.selectedItemId)
    }

    private fun clearAllFragments() {
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { fragment ->
            transaction.remove(fragment)
        }
        transaction.commitNow()
        activeFragment = null
    }

    private fun switchFragment(target: Fragment) {
        if (target === activeFragment) return

        val transaction = supportFragmentManager.beginTransaction()

        activeFragment?.let { transaction.hide(it) }

        if (!target.isAdded) {
            transaction.add(R.id.fragment_container, target)
        } else {
            transaction.show(target)
        }

        transaction.commit()
        activeFragment = target
    }
}
