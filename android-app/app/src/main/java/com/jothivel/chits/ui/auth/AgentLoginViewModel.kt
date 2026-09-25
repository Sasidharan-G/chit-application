package com.jothivel.chits.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jothivel.chits.data.firebase.AgentLoginResult
import com.jothivel.chits.data.firebase.AgentAuthRepository
import kotlinx.coroutines.launch

/**
 * Labour/field-agent counterpart to [LoginViewModel]. Kept as a separate Kotlin ViewModel
 * (rather than folded into the Java admin one) so the existing PIN-login path stays untouched —
 * this one talks to Firestore via [AgentAuthRepository] instead of [com.jothivel.chits.utils.AppPreferences].
 */
class AgentLoginViewModel(application: Application) : AndroidViewModel(application) {

    val loginSuccess = MutableLiveData<Boolean>()
    val loginError = MutableLiveData<String?>()
    /** Changes on every error, even with the same message - see [LoginViewModel.getErrorSeq]. */
    val errorSeq = MutableLiveData(0)
    val isLoading = MutableLiveData(false)

    private fun fail(message: String) {
        isLoading.value = false
        loginError.value = message
        errorSeq.value = (errorSeq.value ?: 0) + 1
    }

    fun login(phone: String, pin: String) {
        if (phone.length != 10) return fail("Enter your 10-digit mobile number")
        if (pin.length != 4) return fail("Enter your 4-digit PIN")
        isLoading.value = true
        viewModelScope.launch {
            when (val result = AgentAuthRepository.login(getApplication(), phone.trim(), pin)) {
                is AgentLoginResult.Success -> {
                    isLoading.value = false
                    loginSuccess.value = true
                }
                is AgentLoginResult.Failure -> fail(result.message)
            }
        }
    }
}
