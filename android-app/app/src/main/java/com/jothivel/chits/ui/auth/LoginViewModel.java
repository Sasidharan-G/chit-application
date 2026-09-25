package com.jothivel.chits.ui.auth;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class LoginViewModel extends AndroidViewModel {

    private static final ExecutorService PIN_CHECK = Executors.newSingleThreadExecutor();

    private final MutableLiveData<Boolean> loginSuccess = new MutableLiveData<>();
    private final MutableLiveData<String> loginError = new MutableLiveData<>();
    // Changes on EVERY error, even when the message is identical. The login screen keys its shake /
    // clear-the-dots reaction on this - keyed on the message alone, a second wrong PIN (same text)
    // did nothing, the four dots stayed filled and the keypad stayed locked: the "app hangs" bug.
    private final MutableLiveData<Integer> errorSeq = new MutableLiveData<>(0);
    private final AtomicInteger errorCounter = new AtomicInteger();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

    private final com.jothivel.chits.utils.AppPreferences appPreferences;

    public LoginViewModel(@NonNull Application application) {
        super(application);
        appPreferences = new com.jothivel.chits.utils.AppPreferences(application);
    }

    public LiveData<Boolean> getLoginSuccess() { return loginSuccess; }
    public LiveData<String> getLoginError() { return loginError; }
    public LiveData<Integer> getErrorSeq() { return errorSeq; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }

    private void fail(String message) {
        loginError.postValue(message);
        errorSeq.postValue(errorCounter.incrementAndGet());
    }

    public void setupAdminProfile(String name, String phone, String username, String pin) {
        if (name == null || name.isEmpty() || phone == null || phone.isEmpty() || username == null || username.isEmpty() || pin == null || pin.isEmpty()) {
            fail("All fields are required");
            return;
        }
        if (pin.length() < 4) {
            fail("PIN must be at least 4 digits");
            return;
        }

        appPreferences.saveAdminProfile(name, phone, username);
        appPreferences.savePin(pin);
        appPreferences.setAdminSetup(true);
        // Logging in as Admin must always win over any cached Labour/agent session from a
        // previous login on this device. Otherwise the app keeps routing to the agent flow
        // even after a correct admin PIN, since getUserRole() only resets via this call.
        appPreferences.clearAgentSession();
        loginSuccess.setValue(true);
    }

    public void verifyPin(String pin) {
        if (pin == null || pin.isEmpty()) {
            fail("Enter your PIN");
            return;
        }

        String throttleMessage = appPreferences.adminPinThrottleMessage();
        if (throttleMessage != null) {
            fail(throttleMessage);
            return;
        }

        // The PIN hash takes a moment on purpose (20,000 rounds); keep it off the UI thread.
        isLoading.setValue(true);
        PIN_CHECK.execute(() -> {
            if (appPreferences.verifyPin(pin)) {
                appPreferences.clearAdminPinFailures();
                // One login, one phone: with a Cloud account connected, refuse this login while the
                // admin is live on another phone (offline or unreachable cloud never blocks).
                String blocked = com.jothivel.chits.data.firebase.SessionGuard.adminLoginBlockMessage(getApplication());
                if (blocked != null) {
                    fail(blocked);
                } else {
                    // Automatically set admin setup to true so session skipping works
                    appPreferences.setAdminSetup(true);
                    // See setupAdminProfile() above: clear any stale agent session so the app routes
                    // to the admin flow, not the last-used Labour session's role.
                    appPreferences.clearAgentSession();
                    loginSuccess.postValue(true);
                }
            } else {
                appPreferences.recordAdminPinFailure();
                fail("Wrong PIN. Try again.");
            }
            isLoading.postValue(false);
        });
    }
}
