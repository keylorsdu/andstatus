/* 
 * Copyright (C) 2014 yvolk (Yuri Volkov), http://yurivolkov.com
 * Copyright (C) 2010 Brion N. Emde, "BLOA" example, http://github.com/brione/Brion-Learns-OAuth 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app.account;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import oauth.signpost.OAuth;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;

import org.andstatus.app.ActivityRequestCode;
import org.andstatus.app.HelpActivity;
import org.andstatus.app.IntentExtra;
import org.andstatus.app.R;
import org.andstatus.app.TimelineActivity;
import org.andstatus.app.account.MyAccount.CredentialsVerificationStatus;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.MySettingsActivity;
import org.andstatus.app.context.MyPreferences;
import org.andstatus.app.net.http.ConnectionException;
import org.andstatus.app.net.http.HttpConnection;
import org.andstatus.app.origin.Origin;
import org.andstatus.app.origin.PersistentOriginList;
import org.andstatus.app.service.MyServiceManager;
import org.andstatus.app.service.MyServiceState;
import org.andstatus.app.util.DialogFactory;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.TriState;
import org.json.JSONException;
import org.json.JSONObject;

import android.view.*;

/**
 * Add new or edit existing account
 * 
 * @author yvolk@yurivolkov.com
 */
public class AccountSettingsActivity extends Activity {
    private static final String TAG = AccountSettingsActivity.class.getSimpleName();
    private static final String FRAGMENT_TAG = AccountSettingsFragment.class.getSimpleName();

    /**
     * This is single list of (in fact, enums...) of Message/Dialog IDs
     */
    private static final int MSG_NONE = 1;
    private static final int MSG_ACCOUNT_VALID = 2;
    private static final int MSG_ACCOUNT_INVALID = 3;
    private static final int MSG_CONNECTION_EXCEPTION = 5;
    private static final int MSG_CREDENTIALS_OF_OTHER_USER = 7;

    /**
     * We are going to finish/restart this Activity
     */
    private boolean mIsFinishing = false;
    private boolean overrideBackActivity = false;
    
    private StateOfAccountChangeProcess state = null;

    private StringBuilder mLatestErrorMessage = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MyContextHolder.initialize(this, this);
        MyContextHolder.upgradeIfNeeded(this);
        if (HelpActivity.startFromActivity(this)) {
            return;
        }

        MyPreferences.loadTheme(this);
        getFragmentManager().beginTransaction()
        .replace(android.R.id.content, new AccountSettingsFragment(), FRAGMENT_TAG)
        .commit();
    }
    
    private View findFramentViewById(int id) {
        return getFragmentManager().findFragmentByTag(FRAGMENT_TAG).getView().findViewById(id);
    }
    
    protected boolean selectOrigin() {
        Intent i = new Intent(AccountSettingsActivity.this, PersistentOriginList.class);
        i.setAction(Intent.ACTION_INSERT);
        startActivityForResult(i, ActivityRequestCode.SELECT_ORIGIN.id);
        return true;
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        restoreState(intent, "onNewIntent");
    }

    /**
     * Restore previous state and set the Activity mode depending on input (Intent).
     * We should decide if we should use the stored state or a newly created one
     * @param intent
     * @param calledFrom - for logging only
     * @param savedInstanceState
     */
    protected void restoreState(Intent intent, String calledFrom) {
        String message = "";
        if (state == null)  {
            state =  StateOfAccountChangeProcess.fromStoredState();
            message += (state.restored ? "Old state restored; " : "No previous state; ");
        } else {
            message += "State existed and " + (state.restored ? "was restored earlier; " : "was not restored earlier; ");
        }
        StateOfAccountChangeProcess newState = StateOfAccountChangeProcess.fromIntent(intent);
        if (state.actionCompleted || newState.useThisState) {
            message += "New state; ";
            state = newState;
            if (state.originShouldBeSelected) {
                selectOrigin();
            } else if (state.accountShouldBeSelected) {
                AccountSelector.selectAccount(this, 0, ActivityRequestCode.SELECT_ACCOUNT);
                message += "Select account; ";
            }
            message += "action=" + state.getAccountAction() + "; ";

            updateScreen();
        }
        if (state.authenticatiorResponse != null) {
            message += "authenticatiorResponse; ";
        }
        MyLog.v(this, "setState from " + calledFrom +"; " + message + "intent=" + intent.toUri(0));
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (ActivityRequestCode.fromId(requestCode)) {
            case SELECT_ACCOUNT:
                onAccountSelected(resultCode, data);
                break;
            case SELECT_ORIGIN:
                onOriginSelected(resultCode, data);
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private void onAccountSelected(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            state.builder = MyAccount.Builder.newOrExistingFromAccountName(MyContextHolder.get(), data.getStringExtra(IntentExtra.ACCOUNT_NAME.key), TriState.UNKNOWN);
            if (!state.builder.isPersistent()) {
                mIsFinishing = true;
            }
        } else {
            mIsFinishing = true;
        }
        if (!mIsFinishing) {
            MyLog.v(this, "Switching to the selected account");
            MyContextHolder.get().persistentAccounts().setCurrentAccount(state.builder.getAccount());
            state.setAccountAction(Intent.ACTION_EDIT);
            updateScreen();
        } else {
            MyLog.v(this, "No account supplied, finishing");
            finish();
        }
    }

    private void onOriginSelected(int resultCode, Intent data) {
        Origin origin = Origin.Builder.buildUnknown();
        if (resultCode == RESULT_OK) {
            origin = MyContextHolder.get().persistentOrigins()
                    .fromName(data.getStringExtra(IntentExtra.ORIGIN_NAME.key));
            if (origin.isPersistent()
                    && state.getAccount().getOriginId() != origin.getId()) {
                // If we have changed the System, we should recreate the Account
                state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                        MyContextHolder.get(), 
                        AccountName.fromOriginAndUserNames(
                                MyContextHolder.get(),
                                origin.getName(),
                                state.getAccount().getUsername()).toString(),
                        TriState.fromBoolean(state.getAccount().isOAuth()));
                updateScreen();
            }
        }
        if (!origin.isPersistent()) {
            closeAndGoBack();
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.account_settings, menu);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.remove_account_menu_id);
        item.setEnabled(state.builder.isPersistent());
        item.setVisible(state.builder.isPersistent());
        
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                closeAndGoBack();
                return true;
            case R.id.preferences_menu_id:
                startMyPreferenceActivity();
                break;
            case R.id.remove_account_menu_id:
                DialogFactory.showYesCancelDialog(getFragmentManager().findFragmentByTag(FRAGMENT_TAG), R.string.remove_account_dialog_title, 
                        R.string.remove_account_dialog_text, 
                        ActivityRequestCode.REMOVE_ACCOUNT);                
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startMyPreferenceActivity() {
        finish();
        startActivity(new Intent(this, MySettingsActivity.class));
    }
    
    private void updateScreen() {
        if (getFragmentManager().findFragmentByTag(FRAGMENT_TAG) == null) {
            return;
        }
        showTitle();
        showErrors();
        showOrigin();
        showUsername();
        showPassword();
        showAccountState();
        showAddAccountButton();
        showVerifyCredentialsButton();
        showDefaultAccountCheckbox();
    }

    private void showTitle() {
        MyAccount ma = state.getAccount();
        String title = getText(R.string.account_settings_activity_title).toString();
        if (ma.isValid()) {
            title += " - " + ma.getAccountName();
        }
        getActionBar().setTitle(title);
    }

    private void showErrors() {
        showTextView(R.id.latest_error_label, R.string.latest_error_label, 
                mLatestErrorMessage.length() > 0);
        showTextView(R.id.latest_error, mLatestErrorMessage, 
                mLatestErrorMessage.length() > 0);
    }

    private void showOrigin() {
        MyAccount ma = state.getAccount();
        TextView view = (TextView) findFramentViewById(R.id.origin_name);
        view.setText(this.getText(R.string.title_preference_origin_system)
                                  .toString().replace("{0}", ma.getOrigin().getName())
                                  .replace("{1}", ma.getOrigin().getOriginType().getTitle()));
    }

    private void showUsername() {
        MyAccount ma = state.getAccount();
        showTextView(R.id.username_label,
                ma.alternativeTermForResourceId(R.string.title_preference_username),
                state.builder.isPersistent() || ma.isUsernameNeededToStartAddingNewAccount());
        EditText usernameEditable = (EditText) findFramentViewById(R.id.username);
        if (state.builder.isPersistent() || !ma.isUsernameNeededToStartAddingNewAccount()) {
            usernameEditable.setVisibility(View.GONE);
        } else {
            usernameEditable.setVisibility(View.VISIBLE);
            usernameEditable.setHint(ma.alternativeTermForResourceId(R.string.summary_preference_username));
            usernameEditable.addTextChangedListener(textWatcher);
        }
        if (ma.getUsername().compareTo(usernameEditable.getText().toString()) != 0) {
            usernameEditable.setText(ma.getUsername());
        }
        showTextView(R.id.username_readonly, ma.getUsername(), state.builder.isPersistent());
    }

    private TextWatcher textWatcher = new TextWatcher() {

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // Empty
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Empty
        }

        @Override
        public void afterTextChanged(Editable s) {
            clearError();
        }
    };
    
    private void showPassword() {
        MyAccount ma = state.getAccount();
        boolean isNeeded = ma.getConnection().isPasswordNeeded();
        StringBuilder labelBuilder = new StringBuilder();
        if (isNeeded) {
            labelBuilder.append(this.getText(R.string.summary_preference_password));
            if (TextUtils.isEmpty(ma.getPassword())) {
                labelBuilder.append(": (" + this.getText(R.string.not_set) + ")");
            }
        }
        showTextView(R.id.password_label, labelBuilder.toString(), isNeeded);
        EditText passwordEditable = (EditText) findFramentViewById(R.id.password);
        if (ma.getPassword().compareTo(passwordEditable.getText().toString()) != 0) {
            passwordEditable.setText(ma.getPassword());
        }
        passwordEditable.setVisibility(isNeeded ? View.VISIBLE : View.GONE);
        passwordEditable.setEnabled(!ma.isValidAndSucceeded());
        passwordEditable.addTextChangedListener(textWatcher);
    }

    private void showAccountState() {
        MyAccount ma = state.getAccount();
        StringBuilder summary = null;
        if (state.builder.isPersistent()) {
            switch (ma.getCredentialsVerified()) {
                case SUCCEEDED:
                    summary = new StringBuilder(
                            this.getText(R.string.summary_preference_verify_credentials));
                    break;
                default:
                    if (state.builder.isPersistent()) {
                        summary = new StringBuilder(
                                this.getText(R.string.summary_preference_verify_credentials_failed));
                    } else {
                        if (ma.isOAuth()) {
                            summary = new StringBuilder(
                                    this.getText(R.string.summary_preference_add_account_oauth));
                        } else {
                            summary = new StringBuilder(
                                    this.getText(R.string.summary_preference_add_account_basic));
                        }
                    }
                    break;
            }
        }
        ((TextView) findFramentViewById(R.id.account_state)).setText(summary);
    }
    
    private void showAddAccountButton() {
        TextView textView = showTextView(R.id.add_account, null, !state.builder.isPersistent());
        textView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clearError();
                updateChangedFields();
                updateScreen();
                MyAccount ma = state.getAccount();
                CharSequence error = "";
                boolean addAccountEnabled =  !ma.isUsernameNeededToStartAddingNewAccount()
                        || ma.isUsernameValid();
                if (addAccountEnabled) {
                    if (!ma.isOAuth() && !ma.getCredentialsPresent()) {
                        addAccountEnabled =  false;
                        error = getText(R.string.title_preference_password);
                    }
                } else {
                    error = getText(ma.alternativeTermForResourceId(R.string.title_preference_username));
                }
                if (addAccountEnabled) {
                    verifyCredentials(true);
                } else {
                    appendError(getText(R.string.error_invalid_value) + ": " + error);
                }
            }
        });
    }

    private void clearError() {
        if (mLatestErrorMessage.length() > 0) {
            mLatestErrorMessage.setLength(0);
            showErrors();
        }
    }
    
    private void showVerifyCredentialsButton() {
        TextView textView = showTextView(
                R.id.verify_credentials,
                state.getAccount().isValidAndSucceeded()
                        ? R.string.title_preference_verify_credentials
                        : R.string.title_preference_verify_credentials_failed,
                state.builder.isPersistent());
        textView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                clearError();
                updateChangedFields();
                updateScreen();
                verifyCredentials(true);
            }
        });
    }
    
    private void showDefaultAccountCheckbox() {
        MyAccount ma = state.getAccount();
        boolean isDefaultAccount = ma.getAccountName().equals(MyContextHolder.get().persistentAccounts().getDefaultAccountName());
        CheckBox checkBox= (CheckBox) findFramentViewById(R.id.is_default_account);
        checkBox.setVisibility(state.builder.isPersistent() ? View.VISIBLE : View.GONE);
        checkBox.setEnabled(!isDefaultAccount);
        checkBox.setChecked(isDefaultAccount);
        checkBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    MyContextHolder.get().persistentAccounts().setDefaultAccount(state.getAccount());
                    updateScreen();
                }
            }
        });
    }

    private TextView showTextView(int textViewId, int textResourceId, boolean isVisible) {
        return showTextView(textViewId, textResourceId == 0 ? null : getText(textResourceId),
                isVisible);
    }
    
    private TextView showTextView(int textViewId, CharSequence text, boolean isVisible) {
        TextView textView = (TextView) findFramentViewById(textViewId);
        if (!TextUtils.isEmpty(text)) {
            textView.setText(text);
        }
        textView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        return textView;
    }

    @Override
    protected void onResume() {
        super.onResume();
        MyContextHolder.get().setInForeground(true);

        MyContextHolder.initialize(this, this);
        MyServiceManager.setServiceUnavailable();
        MyServiceManager.stopService();
        
        updateScreen();
        
        Uri uri = getIntent().getData();
        if (uri != null) {
            if (MyLog.isLoggable(TAG, MyLog.DEBUG)) {
                MyLog.d(TAG, "uri=" + uri.toString());
            }
            if (HttpConnection.CALLBACK_URI.getScheme().equals(uri.getScheme())) {
                // To prevent repeating of this task
                getIntent().setData(null);
                // This activity was started by Twitter ("Service Provider")
                // so start second step of OAuth Authentication process
                new OAuthAcquireAccessTokenTask().execute(uri);
                // and return back to default screen
                overrideBackActivity = true;
            }
        }
    }

    /**
     * Verify credentials
     * 
     * @param reVerify true - Verify only if we didn't do this yet
     */
    private void verifyCredentials(boolean reVerify) {
        MyAccount ma = state.getAccount();
        if (reVerify || ma.getCredentialsVerified() == CredentialsVerificationStatus.NEVER) {
            MyServiceManager.setServiceUnavailable();
            MyServiceState state2 = MyServiceManager.getServiceState(); 
            if (state2 != MyServiceState.STOPPED) {
                MyServiceManager.stopService();
                if (state2 != MyServiceState.UNKNOWN) {
                    appendError(getText(R.string.system_is_busy_try_later) + " (" + state2 + ")");
                    return;
                }
            }
            if (ma.getCredentialsPresent()) {
                // Credentials are present, so we may verify them
                // This is needed even for OAuth - to know Twitter Username
                new VerifyCredentialsTask().execute();
            } else {
                if (ma.isOAuth() && reVerify) {
                    // Credentials are not present,
                    // so start asynchronous OAuth Authentication process 
                    if (!ma.areClientKeysPresent()) {
                        new OAuthRegisterClientTask().execute();
                    } else {
                        new OAuthAcquireRequestTokenTask().execute();
                        // and return back to default screen
                        overrideBackActivity = true;
                    }
                }
            }

        }
    }

    private void updateChangedFields() {
        if (!state.builder.isPersistent()) {
            EditText usernameEditable = (EditText) findFramentViewById(R.id.username);
            String username = usernameEditable.getText().toString();
            if (username.compareTo(state.getAccount().getUsername()) != 0) {
                boolean isOAuth = state.getAccount().isOAuth();
                String originName = state.getAccount().getOrigin().getName();
                state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                        MyContextHolder.get(), 
                        AccountName.fromOriginAndUserNames(MyContextHolder.get(), 
                                originName, username).toString(),
                        TriState.fromBoolean(isOAuth));
            }
        }
        EditText passwordEditable = (EditText) findFramentViewById(R.id.password);
        if (state.getAccount().getPassword().compareTo(passwordEditable.getText().toString()) != 0) {
            state.builder.setPassword(passwordEditable.getText().toString());
        }
    }

    private void appendError(CharSequence errorMessage) {
        if (TextUtils.isEmpty(errorMessage)) {
            return;
        }
        if (mLatestErrorMessage.length()>0) {
            mLatestErrorMessage.append("/n");
        }
        mLatestErrorMessage.append(errorMessage);
        showErrors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        state.save();
        if (mIsFinishing) {
            MyContextHolder.release();
            if (overrideBackActivity) {
                returnToOurActivity();
            }
        }
        MyContextHolder.get().setInForeground(false);
    }

    private void returnToOurActivity() {
        Class<? extends Activity> ourActivity;
        MyContextHolder.initialize(this, this);
        if (MyContextHolder.get().persistentAccounts().size() > 1) {
            ourActivity = MySettingsActivity.class;
        } else {
            ourActivity = TimelineActivity.class;
        }
        MyLog.v(this, "Returning to " + ourActivity.getSimpleName());
        Intent i = new Intent(this, ourActivity);
        // On modifying activity back stack see http://stackoverflow.com/questions/11366700/modification-of-the-back-stack-in-android
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    /**
     * This semaphore helps to avoid ripple effect: changes in MyAccount cause
     * changes in this activity ...
     */
    private boolean somethingIsBeingProcessed = false;
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            closeAndGoBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 
     * Mark the action completed, close this activity and go back to the proper screen.
     * Return result to the caller if necessary.
     * See also {@link com.android.email.activity.setup.AccountSetupBasics.finish}
     * 
     * @return
     */
    private void closeAndGoBack() {
        // Explicitly save MyAccount only on "Back key" 
        state.builder.save();
        String message = "";
        state.actionCompleted = true;
        overrideBackActivity = true;
        if (state.authenticatiorResponse != null) {
            // We should return result back to AccountManager
            overrideBackActivity = false;
            if (state.actionSucceeded) {
                if (state.builder.isPersistent()) {
                    // Pass the new/edited account back to the account manager
                    Bundle result = new Bundle();
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, state.getAccount().getAccountName());
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE,
                            AuthenticatorService.ANDROID_ACCOUNT_TYPE);
                    state.authenticatiorResponse.onResult(result);
                    message += "authenticatiorResponse; account.name=" + state.getAccount().getAccountName() + "; ";
                }
            } else {
                state.authenticatiorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
        }
        // Forget old state
        state.forget();
        if (!mIsFinishing) {
            MyLog.v(this, "finish: action=" + state.getAccountAction() + "; " + message);
            mIsFinishing = true;
            finish();
        }
    }
    
    /**
     * Start system activity which allow to manage list of accounts
     * See <a href="https://groups.google.com/forum/?fromgroups#!topic/android-developers/RfrIb5V_Bpo">per account settings in Jelly Beans</a>. 
     * For versions prior to Jelly Bean see <a href="http://stackoverflow.com/questions/3010103/android-how-to-create-intent-to-open-the-activity-that-displays-the-accounts">
     *  Android - How to Create Intent to open the activity that displays the “Accounts & Sync settings” screen</a>
     */
    public static void startManageExistingAccounts(android.content.Context context) {
        Intent intent;
        // TODO: Figure out more concrete Intent to the list of AndStatus accounts
        intent = new Intent(context, AccountSettingsActivity.class);
        context.startActivity(intent);
    }
    
    public static void startAddNewAccount(android.content.Context context) {
        Intent intent;
        intent = new Intent(context, AccountSettingsActivity.class);
        intent.setAction(Intent.ACTION_INSERT);
        context.startActivity(intent);
    }
    
    public StateOfAccountChangeProcess getState() {
        return state;
    }
    
    /**
     * Step 1 of 3 of the OAuth Authentication
     * Needed in case we don't have the AndStatus Client keys for the Microblogging system
     */
    private class OAuthRegisterClientTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_registering_client),
                    getText(R.string.dialog_summary_registering_client),
                // duration indeterminate
                    true, 
                // not cancel-able
                    false); 
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            boolean requestSucceeded = false;
            String message = "";
            String message2 = "";

            try {
                state.builder.getOriginConfig();
                if (!state.getAccount().areClientKeysPresent()) {
                    state.builder.registerClient();
                } 
                requestSucceeded = state.getAccount().areClientKeysPresent();
            } catch (ConnectionException e) {
                message = e.getMessage();
                MyLog.e(this, e);
            }
            
            try {
                if (!requestSucceeded) {
                    message2 = AccountSettingsActivity.this
                            .getString(R.string.dialog_title_authentication_failed);
                    if (message != null && message.length() > 0) {
                        message2 = message2 + ": " + message;
                    }
                    MyLog.d(TAG, message2);
                }

                jso = new JSONObject();
                jso.put("succeeded", requestSucceeded);
                jso.put("message", message2);
            } catch (JSONException e) {
                MyLog.e(this, e);
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            DialogFactory.dismissSafely(dlg);
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    if (succeeded) {
                        String accountName = state.getAccount().getAccountName();
                        MyContextHolder.get().persistentAccounts().initialize();
                        state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                                MyContextHolder.get(), accountName, TriState.TRUE);
                        updateScreen();
                        new OAuthAcquireRequestTokenTask().execute();
                        // and return back to default screen
                        overrideBackActivity = true;
                    } else {
                        appendError(message);
                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        updateScreen();
                    }
                } catch (JSONException e) {
                    MyLog.e(this, e);
                }
            }
        }
    }
    
    
    /**
     * Task 2 of 3 required for OAuth Authentication.
     * See http://www.snipe.net/2009/07/writing-your-first-twitter-application-with-oauth/
     * for good OAuth Authentication flow explanation.
     *  
     * During this task:
     * 1. AndStatus ("Consumer") Requests "Request Token" from Twitter ("Service provider"), 
     * 2. Waits for that Request Token
     * 3. Consumer directs User to the Service Provider: opens Twitter site in Internet Browser window
     *    in order to Obtain User Authorization.
     * 4. This task ends.
     * 
     * What will occur later:
     * 5. After User Authorized AndStatus in the Internet Browser,
     *    Twitter site will redirect User back to
     *    AndStatus and then the second OAuth task will start.
     *   
     * @author yvolk@yurivolkov.com This code is based on "BLOA" example,
     *         http://github.com/brione/Brion-Learns-OAuth yvolk: I had to move
     *         this code from OAuthActivity here in order to be able to show
     *         ProgressDialog and to get rid of any "Black blank screens"
     */
    private class OAuthAcquireRequestTokenTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_acquiring_a_request_token),
                    getText(R.string.dialog_summary_acquiring_a_request_token),
                    // indeterminate duration
                    true, 
                    // not cancelable
                    false 
                    );
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            boolean requestSucceeded = false;
            String message = "";
            String message2 = "";
            try {
                MyAccount ma = state.getAccount();
                MyLog.v(this, "Retrieving request token for " + ma);
                OAuthConsumer consumer = state.getAccount().getOAuthConsumerAndProvider().getConsumer();

                // This is really important. If you were able to register your
                // real callback Uri with Twitter, and not some fake Uri
                // like I registered when I wrote this example, you need to send
                // null as the callback Uri in this function call. Then
                // Twitter will correctly process your callback redirection
                String authUrl = state.getAccount().getOAuthConsumerAndProvider().getProvider()
                        .retrieveRequestToken(consumer, HttpConnection.CALLBACK_URI.toString());
                state.setRequestTokenWithSecret(consumer.getToken(), consumer.getTokenSecret());

                // This is needed in order to complete the process after redirect
                // from the Browser to the same activity.
                state.actionCompleted = false;
                
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                cookieManager.removeAllCookie();                

                // Start Web view (looking just like Web Browser)
                Intent i = new Intent(AccountSettingsActivity.this, AccountSettingsWebActivity.class);
                i.putExtra(AccountSettingsWebActivity.EXTRA_URLTOOPEN, authUrl);
                AccountSettingsActivity.this.startActivity(i);

                requestSucceeded = true;
            } catch (OAuthMessageSignerException e) {
                message = e.getMessage();
                MyLog.e(this, e);
            } catch (OAuthNotAuthorizedException e) {
                message = e.getMessage();
                MyLog.e(this, e);
            } catch (OAuthExpectationFailedException e) {
                message = e.getMessage();
                MyLog.e(this, e);
            } catch (OAuthCommunicationException e) {
                message = e.getMessage();
                MyLog.e(this, e);
            }

            try {
                if (!requestSucceeded) {
                    message2 = AccountSettingsActivity.this
                            .getString(R.string.dialog_title_authentication_failed);
                    if (message != null && message.length() > 0) {
                        message2 = message2 + ": " + message;
                    }
                    MyLog.d(TAG, message2);
                    
                    state.builder.clearClientKeys();
                }

                jso = new JSONObject();
                jso.put("succeeded", requestSucceeded);
                jso.put("message", message2);
            } catch (JSONException e) {
                MyLog.i(this, e);
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            DialogFactory.dismissSafely(dlg);
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    if (succeeded) {
                        // Finish this activity in order to start properly 
                        // after redirection from Browser
                        // Because of initializations in onCreate...
                        AccountSettingsActivity.this.finish();
                    } else {
                        appendError(message);
                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        updateScreen();
                    }
                } catch (JSONException e) {
                    MyLog.e(this, e);
                }
            }
        }
    }
    
    /**
     * Task 3 of 3 required for OAuth Authentication.
     *  
     * During this task:
     * 1. AndStatus ("Consumer") exchanges "Request Token", 
     *    obtained earlier from Twitter ("Service provider"),
     *    for "Access Token". 
     * 2. Stores the Access token for all future interactions with Twitter.
     * 
     * @author yvolk@yurivolkov.com This code is based on "BLOA" example,
     *         http://github.com/brione/Brion-Learns-OAuth yvolk: I had to move
     *         this code from OAuthActivity here in order to be able to show
     *         ProgressDialog and to get rid of any "Black blank screens"
     */
    private class OAuthAcquireAccessTokenTask extends AsyncTask<Uri, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_acquiring_an_access_token),
                    getText(R.string.dialog_summary_acquiring_an_access_token),
                 // indeterminate duration
                    true, 
                 // not cancelable
                    false 
                    ); 
        }

        @Override
        protected JSONObject doInBackground(Uri... uris) {
            JSONObject jso = null;

            String message = "";
            
            boolean authenticated = false;

            if (state.getAccount().getOAuthConsumerAndProvider() == null) {
                message = "Connection is not OAuth";
                MyLog.e(this, message);
            } else {
                // We don't need to worry about any saved states: we can reconstruct
                // the state

                Uri uri = uris[0];
                if (uri != null && HttpConnection.CALLBACK_URI.getHost().equals(uri.getHost())) {
                    String token = state.getRequestToken();
                    String secret = state.getRequestSecret();

                    state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.NEVER);
                    try {
                        // Clear the request stuff, we've used it already
                        state.setRequestTokenWithSecret(null, null);

                        OAuthConsumer consumer = state.getAccount().getOAuthConsumerAndProvider().getConsumer();
                        if (!(token == null || secret == null)) {
                            consumer.setTokenWithSecret(token, secret);
                        }
                        String otoken = uri.getQueryParameter(OAuth.OAUTH_TOKEN);
                        String verifier = uri.getQueryParameter(OAuth.OAUTH_VERIFIER);

                        /*
                         * yvolk 2010-07-08: It appeared that this may be not true:
                         * Assert.assertEquals(otoken, mConsumer.getToken()); (e.g.
                         * if User denied access during OAuth...) hence this is not
                         * Assert :-)
                         */
                        if (otoken != null || consumer.getToken() != null) {
                            state.getAccount().getOAuthConsumerAndProvider().getProvider()
                                .retrieveAccessToken(consumer, verifier);
                            // Now we can retrieve the goodies
                            token = consumer.getToken();
                            secret = consumer.getTokenSecret();
                            authenticated = true;
                        }
                    } catch (OAuthMessageSignerException | OAuthNotAuthorizedException
                            | OAuthExpectationFailedException | OAuthCommunicationException e) {
                        message = e.getMessage();
                        MyLog.e(this, e);
                    } finally {
                        if (authenticated) {
                            state.builder.setUserTokenWithSecret(token, secret);
                        }
                    }
                }
            }

            try {
                jso = new JSONObject();
                jso.put("succeeded", authenticated);
                jso.put("message", message);
            } catch (JSONException e) {
                MyLog.e(this, e);
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            DialogFactory.dismissSafely(dlg);
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    MyLog.d(TAG, this.getClass().getName() + " ended, "
                            + (succeeded ? "authenticated" : "authentication failed"));
                    
                    if (succeeded) {
                        // Credentials are present, so we may verify them
                        // This is needed even for OAuth - to know Twitter Username
                        new VerifyCredentialsTask().execute();

                    } else {
                        String message2 = AccountSettingsActivity.this
                        .getString(R.string.dialog_title_authentication_failed);
                        if (message != null && message.length() > 0) {
                            message2 = message2 + ": " + message;
                            MyLog.d(TAG, message);
                        }
                        appendError(message2);
                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        updateScreen();
                    }
                } catch (JSONException e) {
                    MyLog.e(this, e);
                }
            }
        }
    }

    /**
     * Assuming we already have credentials to verify, verify them
     * @author yvolk@yurivolkov.com
     */
    private class VerifyCredentialsTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;
        private boolean skip = false;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_checking_credentials),
                    getText(R.string.dialog_summary_checking_credentials),
                 // indeterminate duration
                    true, 
                 // not cancelable
                    false 
                    );

            synchronized (AccountSettingsActivity.this) {
                if (somethingIsBeingProcessed) {
                    skip = true;
                } else {
                    somethingIsBeingProcessed = true;
                }
            }
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            int what = MSG_NONE;
            String message = "";
            
            if (!skip) {
                what = MSG_ACCOUNT_INVALID;
                try {
                    state.builder.getOriginConfig();
                    if (state.builder.verifyCredentials(true)) {
                        what = MSG_ACCOUNT_VALID;
                    }
                } catch (ConnectionException e) {
                    switch (e.getStatusCode()) {
                        case AUTHENTICATION_ERROR:
                            what = MSG_ACCOUNT_INVALID;
                            break;
                        case CREDENTIALS_OF_OTHER_USER:
                            what = MSG_CREDENTIALS_OF_OTHER_USER;
                            break;
                        default:
                            what = MSG_CONNECTION_EXCEPTION;
                            break;
                    }
                    message = e.toString();
                    MyLog.v(this, e);
                }
            }

            try {
                jso = new JSONObject();
                jso.put("what", what);
                jso.put("message", message);
            } catch (JSONException e) {
                MyLog.e(this, e);
            }
            return jso;
        }

        /**
         * Credentials were verified just now!
         * This is in the UI thread, so we can mess with the UI
         */
        @Override
        protected void onPostExecute(JSONObject jso) {
            DialogFactory.dismissSafely(dlg);
            boolean succeeded = false;
            CharSequence errorMessage = "";
            if (jso != null) {
                try {
                    int what = jso.getInt("what");
                    CharSequence message = jso.getString("message");

                    switch (what) {
                        case MSG_ACCOUNT_VALID:
                            Toast.makeText(AccountSettingsActivity.this, R.string.authentication_successful,
                                    Toast.LENGTH_SHORT).show();
                            succeeded = true;
                            break;
                        case MSG_ACCOUNT_INVALID:
                            errorMessage = getText(R.string.dialog_summary_authentication_failed);
                            break;
                        case MSG_CREDENTIALS_OF_OTHER_USER:
                            errorMessage = getText(R.string.error_credentials_of_other_user);
                            break;
                        case MSG_CONNECTION_EXCEPTION:
                            errorMessage = getText(R.string.error_connection_error) + " \n" + message;
							MyLog.i(this, errorMessage.toString());
                            break;
                        default:
                            break;
                    }
                } catch (JSONException e) {
                    MyLog.e(this, e);
                }
            }
            if (!skip) {
                StateOfAccountChangeProcess state2 = AccountSettingsActivity.this.state;
                // Note: MyAccount was already saved inside MyAccount.verifyCredentials
                // Now we only have to deal with the state
               
                state2.actionSucceeded = succeeded;
                if (succeeded) {
                    state2.actionCompleted = true;
                    if (state2.getAccountAction().compareTo(Intent.ACTION_INSERT) == 0) {
                        state2.setAccountAction(Intent.ACTION_EDIT);
                        // TODO: Decide if we need closeAndGoBack() here
                    }
                }
                somethingIsBeingProcessed = false;
            }
            updateScreen();
            appendError(errorMessage);
        }
    }
}
