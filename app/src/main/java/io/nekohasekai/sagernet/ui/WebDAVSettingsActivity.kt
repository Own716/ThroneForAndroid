package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.text.InputType
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.backup.WebDavBackup
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar

class WebDAVSettingsActivity : ThemedActivity() {
    
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.layout_webdav_settings)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.webdav_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, WebDAVSettingsFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class WebDAVSettingsFragment : PreferenceFragmentCompat(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
        private var lastClickTime = 0L
        private val DEBOUNCE_TIME = 1000L  // ignore repeated taps within a second
        private var isFragmentAlive = true

        private fun isClickAllowed(): Boolean {
            val currentTime = System.currentTimeMillis()
            val isAllowed = currentTime - lastClickTime > DEBOUNCE_TIME
            if (isAllowed) {
                lastClickTime = currentTime
            }
            return isAllowed
        }

        override fun onDestroy() {
            isFragmentAlive = false
            super.onDestroy()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.applyListInsets()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.configurationStore
            addPreferencesFromResource(R.xml.webdav_preferences)
            
            findPreference<EditTextPreference>("webdavServer")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine()
                    editText.setSelection(editText.text.length)
                }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            
            findPreference<EditTextPreference>("webdavUsername")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine()
                    editText.setSelection(editText.text.length)
                }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            
            findPreference<EditTextPreference>("webdavPassword")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine()
                    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    editText.setSelection(editText.text.length)
                }
                // Masked summary, like the other password fields
                summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
            }
            
            findPreference<EditTextPreference>("webdavPath")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine()
                    editText.setSelection(editText.text.length)
                }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }

            findPreference<EditTextPreference>(Key.DEVICE_NAME)?.apply {
                setOnBindEditTextListener { editText ->
                    editText.setSingleLine()
                    editText.hint = DataStore.defaultDeviceName()
                    editText.setSelection(editText.text.length)
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                    preference.text?.trim()?.takeIf { it.isNotEmpty() }
                        ?: getString(R.string.webdav_device_name_default, DataStore.defaultDeviceName())
                }
            }

            findPreference<Preference>("webdavTest")?.setOnPreferenceClickListener {
                if (isClickAllowed()) {
                    testWebDAV()
                } else {
                    Snackbar.make(requireView(), R.string.try_again_later, Snackbar.LENGTH_SHORT).show()
                }
                true
            }
        }

        private fun testWebDAV() {
            runOnDefaultDispatcher {
                try {
                    val dav = WebDavBackup.configured()
                        ?: throw Exception(app.getString(R.string.webdav_server_empty))
                    dav.check()

                    onMainDispatcher {
                        if (!isFragmentAlive) return@onMainDispatcher
                        Snackbar.make(
                            requireView(),
                            getString(R.string.webdav_test_success),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    onMainDispatcher {
                        if (!isFragmentAlive) return@onMainDispatcher
                        Snackbar.make(
                            requireView(),
                            getString(R.string.webdav_test_failed, e.readableMessage),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
            return false
        }
    }
}
