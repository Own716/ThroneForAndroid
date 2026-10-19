package io.nekohasekai.sagernet.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.AutoCompleteTextView
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.DnsServerSuggestions

/** A DNS server setting whose dialog offers [suggestions] (DnsServerHistory::Fill), read each time it opens. */
class DnsServerPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    var suggestions: () -> List<String> = { emptyList() }

    init {
        dialogLayoutResource = R.layout.layout_dns_server_dialog
        setOnBindEditTextListener { edit ->
            val field = edit as? AutoCompleteTextView ?: return@setOnBindEditTextListener
            val layout = edit.rootView.findViewById<TextInputLayout>(R.id.server_layout) ?: return@setOnBindEditTextListener
            DnsServerSuggestions.attach(layout, field, suggestions())
        }
    }
}
