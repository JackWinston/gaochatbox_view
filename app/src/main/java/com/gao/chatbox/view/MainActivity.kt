package com.gao.chatbox.view

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.gao.chatbox.view.databinding.ActivityMainBinding
import com.gao.chatbox.view.ui.home.history.HistoryFragment
import com.gao.chatbox.view.ui.home.quickstart.QuickStartFragment
import com.gao.chatbox.view.ui.home.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val quickStartFragment by lazy { QuickStartFragment() }
    private val historyFragment by lazy { HistoryFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchFragment(quickStartFragment)
        }

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
