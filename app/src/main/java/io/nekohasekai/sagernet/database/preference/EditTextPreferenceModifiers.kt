package io.nekohasekai.sagernet.database.preference

import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference

object EditTextPreferenceModifiers {
    object Hosts : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.setHorizontallyScrolling(true)
            editText.setSelection(editText.text.length)
        }
    }

    object Port : EditTextPreference.OnBindEditTextListener {
        private val portLengthFilter = arrayOf(InputFilter.LengthFilter(5))

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.filters = portLengthFilter
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }

    object Number : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }

    /** [Number] that also takes a leading minus sign. */
    object SignedNumber : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }
}
