/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settings.datausage;

import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.net.TrafficStats;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceCategory;
import android.text.format.Formatter;
import android.util.ArraySet;
import android.view.View;
import android.widget.AdapterView;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settings.AppHeader;
import com.android.settings.R;
import com.android.settings.applications.AppInfoBase;
import com.android.settingslib.AppItem;
import com.android.settingslib.Utils;
import com.android.settingslib.net.ChartData;
import com.android.settingslib.net.ChartDataLoader;
import com.android.settingslib.net.UidDetailProvider;

import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;

public class AppDataUsage extends DataUsageBase implements Preference.OnPreferenceChangeListener {

    public static final String ARG_APP_ITEM = "app_item";
    public static final String ARG_NETWORK_TEMPLATE = "network_template";

    private static final String KEY_TOTAL_USAGE = "total_usage";
    private static final String KEY_FOREGROUND_USAGE = "foreground_usage";
    private static final String KEY_BACKGROUND_USAGE = "background_usage";
    private static final String KEY_APP_SETTINGS = "app_settings";
    private static final String KEY_RESTRICT_BACKGROUND = "restrict_background";
    private static final String KEY_APP_LIST = "app_list";
    private static final String KEY_CYCLE = "cycle";
    private static final String KEY_UNRESTRICTED_DATA = "unrestricted_data_saver";

    private static final int LOADER_CHART_DATA = 2;

    private final ArraySet<String> mPackages = new ArraySet<>();
    private Preference mTotalUsage;
    private Preference mForegroundUsage;
    private Preference mBackgroundUsage;
    private Preference mAppSettings;
    private SwitchPreference mRestrictBackground;
    private PreferenceCategory mAppList;

    private Drawable mIcon;
    private CharSequence mLabel;
    private INetworkStatsSession mStatsSession;
    private CycleAdapter mCycleAdapter;

    private long mStart;
    private long mEnd;
    private ChartData mChartData;
    private NetworkTemplate mTemplate;
    private NetworkPolicy mPolicy;
    private AppItem mAppItem;
    private Intent mAppSettingsIntent;
    private SpinnerPreference mCycle;
    private SwitchPreference mUnrestrictedData;
    private DataSaverBackend mDataSaverBackend;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        final Bundle args = getArguments();

        try {
            mStatsSession = services.mStatsService.openSession();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        mAppItem = (args != null) ? (AppItem) args.getParcelable(ARG_APP_ITEM) : null;
        mTemplate = (args != null) ? (NetworkTemplate) args.getParcelable(ARG_NETWORK_TEMPLATE)
                : null;
        if (mTemplate == null) {
            Context context = getContext();
            mTemplate = DataUsageSummary.getDefaultTemplate(context,
                    DataUsageSummary.getDefaultSubscriptionId(context));
        }
        if (mAppItem == null) {
            int uid = (args != null) ? args.getInt(AppInfoBase.ARG_PACKAGE_UID, -1)
                    : getActivity().getIntent().getIntExtra(AppInfoBase.ARG_PACKAGE_UID, -1);
            if (uid == -1) {
                // TODO: Log error.
                getActivity().finish();
            } else {
                addUid(uid);
                mAppItem = new AppItem(uid);
                mAppItem.addUid(uid);
            }
        } else {
            for (int i = 0; i < mAppItem.uids.size(); i++) {
                addUid(mAppItem.uids.keyAt(i));
            }
        }
        addPreferencesFromResource(R.xml.app_data_usage);

        mTotalUsage = findPreference(KEY_TOTAL_USAGE);
        mForegroundUsage = findPreference(KEY_FOREGROUND_USAGE);
        mBackgroundUsage = findPreference(KEY_BACKGROUND_USAGE);

        mCycle = (SpinnerPreference) findPreference(KEY_CYCLE);
        mCycleAdapter = new CycleAdapter(getContext(), mCycle, mCycleListener, false);

        if (UserHandle.isApp(mAppItem.key)) {
            if (mPackages.size() != 0) {
                PackageManager pm = getPackageManager();
                try {
                    ApplicationInfo info = pm.getApplicationInfo(mPackages.valueAt(0), 0);
                    mIcon = info.loadIcon(pm);
                    mLabel = info.loadLabel(pm);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            mRestrictBackground = (SwitchPreference) findPreference(KEY_RESTRICT_BACKGROUND);
            mRestrictBackground.setOnPreferenceChangeListener(this);
            mUnrestrictedData = (SwitchPreference) findPreference(KEY_UNRESTRICTED_DATA);
            mUnrestrictedData.setOnPreferenceChangeListener(this);
            mDataSaverBackend = new DataSaverBackend(getContext());
            mAppSettings = findPreference(KEY_APP_SETTINGS);

            mAppSettingsIntent = new Intent(Intent.ACTION_MANAGE_NETWORK_USAGE);
            mAppSettingsIntent.addCategory(Intent.CATEGORY_DEFAULT);

            PackageManager pm = getPackageManager();
            boolean matchFound = false;
            for (String packageName : mPackages) {
                mAppSettingsIntent.setPackage(packageName);
                if (pm.resolveActivity(mAppSettingsIntent, 0) != null) {
                    matchFound = true;
                    break;
                }
            }
            if (!matchFound) {
                removePreference(KEY_APP_SETTINGS);
                mAppSettings = null;
            }

            if (mPackages.size() > 1) {
                mAppList = (PreferenceCategory) findPreference(KEY_APP_LIST);
                for (int i = 1 ; i < mPackages.size(); i++) {
                    new AppPrefLoader().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                            mPackages.valueAt(i));
                }
            } else {
                removePreference(KEY_APP_LIST);
            }
        } else {
            final int userId = UidDetailProvider.getUserIdForKey(mAppItem.key);
            final UserManager um = UserManager.get(getActivity());
            final UserInfo info = um.getUserInfo(userId);
            final PackageManager pm = getPackageManager();
            mIcon = Utils.getUserIcon(getActivity(), um, info);
            mLabel = Utils.getUserLabel(getActivity(), info);
            removePreference(KEY_UNRESTRICTED_DATA);
            removePreference(KEY_APP_SETTINGS);
            removePreference(KEY_RESTRICT_BACKGROUND);
            removePreference(KEY_APP_LIST);
        }
    }

    @Override
    public void onDestroy() {
        TrafficStats.closeQuietly(mStatsSession);
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        mPolicy = services.mPolicyEditor.getPolicy(mTemplate);
        getLoaderManager().restartLoader(LOADER_CHART_DATA,
                ChartDataLoader.buildArgs(mTemplate, mAppItem), mChartDataCallbacks);
        updatePrefs();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mRestrictBackground) {
            setAppRestrictBackground(!(Boolean) newValue);
            return true;
        } else if (preference == mUnrestrictedData) {
            mDataSaverBackend.setIsWhitelisted(mAppItem.key, (Boolean) newValue);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == mAppSettings) {
            // TODO: target towards entire UID instead of just first package
            getActivity().startActivityAsUser(mAppSettingsIntent, new UserHandle(
                    UserHandle.getUserId(mAppItem.key)));
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void updatePrefs() {
        if (mRestrictBackground != null) {
            mRestrictBackground.setChecked(!getAppRestrictBackground());
        }
        if (mUnrestrictedData != null) {
            mUnrestrictedData.setChecked(mDataSaverBackend.isWhitelisted(mAppItem.key));
        }
    }

    private void addUid(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (int i = 0; i < packages.length; i++) {
                mPackages.add(packages[i]);
            }
        }
    }

    private void bindData() {
        if (mChartData == null || mStart == 0) {
            return;
        }
        final Context context = getContext();
        final long now = System.currentTimeMillis();

        NetworkStatsHistory.Entry entry = null;
        entry = mChartData.detailDefault.getValues(mStart, mEnd, now, entry);
        final long backgroundBytes = entry.rxBytes + entry.txBytes;
        entry = mChartData.detailForeground.getValues(mStart, mEnd, now, entry);
        final long foregroundBytes = entry.rxBytes + entry.txBytes;
        final long totalBytes = backgroundBytes + foregroundBytes;

        mTotalUsage.setSummary(Formatter.formatFileSize(context, totalBytes));
        mForegroundUsage.setSummary(Formatter.formatFileSize(context, foregroundBytes));
        mBackgroundUsage.setSummary(Formatter.formatFileSize(context, backgroundBytes));
    }

    private boolean getAppRestrictBackground() {
        final int uid = mAppItem.key;
        final int uidPolicy = services.mPolicyManager.getUidPolicy(uid);
        return (uidPolicy & POLICY_REJECT_METERED_BACKGROUND) != 0;
    }

    private void setAppRestrictBackground(boolean restrictBackground) {
        final int uid = mAppItem.key;
        services.mPolicyManager.setUidPolicy(
                uid, restrictBackground ? POLICY_REJECT_METERED_BACKGROUND : POLICY_NONE);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View header = setPinnedHeaderView(R.layout.app_header);
        String pkg = mPackages.size() != 0 ? mPackages.valueAt(0) : null;
        int uid = 0;
        try {
            uid = pkg != null ? getPackageManager().getPackageUid(pkg, 0) : 0;
        } catch (PackageManager.NameNotFoundException e) {
        }
        AppHeader.setupHeaderView(getActivity(), mIcon, mLabel,
                pkg, uid, AppHeader.includeAppInfo(this), 0, header, null);
    }

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.APP_DATA_USAGE;
    }

    private AdapterView.OnItemSelectedListener mCycleListener =
            new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            final CycleAdapter.CycleItem cycle = (CycleAdapter.CycleItem) mCycle.getSelectedItem();

            mStart = cycle.start;
            mEnd = cycle.end;
            bindData();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // ignored
        }
    };

    private final LoaderManager.LoaderCallbacks<ChartData> mChartDataCallbacks =
            new LoaderManager.LoaderCallbacks<ChartData>() {
        @Override
        public Loader<ChartData> onCreateLoader(int id, Bundle args) {
            return new ChartDataLoader(getActivity(), mStatsSession, args);
        }

        @Override
        public void onLoadFinished(Loader<ChartData> loader, ChartData data) {
            mChartData = data;
            mCycleAdapter.updateCycleList(mPolicy, mChartData);
            bindData();
        }

        @Override
        public void onLoaderReset(Loader<ChartData> loader) {
        }
    };

    private class AppPrefLoader extends AsyncTask<String, Void, Preference> {
        @Override
        protected Preference doInBackground(String... params) {
            PackageManager pm = getPackageManager();
            String pkg = params[0];
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                Preference preference = new Preference(getPrefContext());
                preference.setIcon(info.loadIcon(pm));
                preference.setTitle(info.loadLabel(pm));
                preference.setSelectable(false);
                return preference;
            } catch (PackageManager.NameNotFoundException e) {
            }
            return null;
        }

        @Override
        protected void onPostExecute(Preference pref) {
            if (pref != null && mAppList != null) {
                mAppList.addPreference(pref);
            }
        }
    }
}