/*
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.widget;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.support.v7.app.ActionBarActivity;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageButton;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.PgpConversionHelper;
import org.sufficientlysecure.keychain.pgp.UncachedSecretKey;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.ui.dialog.CreateKeyDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.ProgressDialogFragment;
import org.sufficientlysecure.keychain.ui.widget.Editor.EditorListener;
import org.sufficientlysecure.keychain.util.Choice;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class SectionView extends LinearLayout implements OnClickListener, EditorListener, Editor {
    private LayoutInflater mInflater;
    private ImageButton mPlusButton;
    private ViewGroup mEditors;
    private TextView mTitle;
    private int mType = 0;
    private EditorListener mEditorListener = null;

    private Choice mNewKeyAlgorithmChoice;
    private int mNewKeySize;
    private boolean mOldItemDeleted = false;
    private ArrayList<String> mDeletedIDs = new ArrayList<String>();
    private ArrayList<UncachedSecretKey> mDeletedKeys = new ArrayList<UncachedSecretKey>();
    private boolean mCanBeEdited = true;

    private ActionBarActivity mActivity;

    private ProgressDialogFragment mGeneratingDialog;

    public static final int TYPE_USER_ID = 1;
    public static final int TYPE_KEY = 2;

    public void setEditorListener(EditorListener listener) {
        mEditorListener = listener;
    }

    public SectionView(Context context) {
        super(context);
        mActivity = (ActionBarActivity) context;
    }

    public SectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivity = (ActionBarActivity) context;
    }

    public ViewGroup getEditors() {
        return mEditors;
    }

    public void setType(int type) {
        mType = type;
        switch (type) {
            case TYPE_USER_ID: {
                mTitle.setText(R.string.section_user_ids);
                break;
            }

            case TYPE_KEY: {
                mTitle.setText(R.string.section_keys);
                break;
            }

            default: {
                break;
            }
        }
    }

    public void setCanBeEdited(boolean canBeEdited) {
        mCanBeEdited = canBeEdited;
        if (!mCanBeEdited) {
            mPlusButton.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onFinishInflate() {
        mInflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        setDrawingCacheEnabled(true);
        setAlwaysDrawnWithCacheEnabled(true);

        mPlusButton = (ImageButton) findViewById(R.id.plusbutton);
        mPlusButton.setOnClickListener(this);

        mEditors = (ViewGroup) findViewById(R.id.editors);
        mTitle = (TextView) findViewById(R.id.title);

        updateEditorsVisible();
        super.onFinishInflate();
    }

    /**
     * {@inheritDoc}
     */
    public void onDeleted(Editor editor, boolean wasNewItem) {
        mOldItemDeleted |= !wasNewItem;
        if (mOldItemDeleted) {
            if (mType == TYPE_USER_ID) {
                mDeletedIDs.add(((UserIdEditor) editor).getOriginalID());
            } else if (mType == TYPE_KEY) {
                mDeletedKeys.add(((KeyEditor) editor).getValue());
            }
        }
        this.updateEditorsVisible();
        if (mEditorListener != null) {
            mEditorListener.onEdited();
        }
    }

    @Override
    public void onEdited() {
        if (mEditorListener != null) {
            mEditorListener.onEdited();
        }
    }

    protected void updateEditorsVisible() {
        final boolean hasChildren = mEditors.getChildCount() > 0;
        mEditors.setVisibility(hasChildren ? View.VISIBLE : View.GONE);
    }

    public boolean needsSaving() {
        //check each view for needs saving, take account of deleted items
        boolean ret = mOldItemDeleted;
        for (int i = 0; i < mEditors.getChildCount(); ++i) {
            Editor editor = (Editor) mEditors.getChildAt(i);
            ret |= editor.needsSaving();
            if (mType == TYPE_USER_ID) {
                ret |= ((UserIdEditor) editor).primarySwapped();
            }
        }
        return ret;
    }

    public boolean primaryChanged() {
        boolean ret = false;
        for (int i = 0; i < mEditors.getChildCount(); ++i) {
            Editor editor = (Editor) mEditors.getChildAt(i);
            if (mType == TYPE_USER_ID) {
                ret |= ((UserIdEditor) editor).primarySwapped();
            }
        }
        return ret;
    }

    public String getOriginalPrimaryID() {
        //NB: this will have to change when we change how Primary IDs are chosen, and so we need to be
        //    careful about where Master key capabilities are stored... multiple primaries and
        //    revoked ones make this harder than the simple case we are continuing to assume here
        for (int i = 0; i < mEditors.getChildCount(); ++i) {
            Editor editor = (Editor) mEditors.getChildAt(i);
            if (mType == TYPE_USER_ID) {
                if (((UserIdEditor) editor).getIsOriginallyMainUserID()) {
                    return ((UserIdEditor) editor).getOriginalID();
                }
            }
        }
        return null;
    }

    public ArrayList<String> getOriginalIDs() {
        ArrayList<String> orig = new ArrayList<String>();
        if (mType == TYPE_USER_ID) {
            for (int i = 0; i < mEditors.getChildCount(); ++i) {
                UserIdEditor editor = (UserIdEditor) mEditors.getChildAt(i);
                if (editor.isMainUserId()) {
                    orig.add(0, editor.getOriginalID());
                } else {
                    orig.add(editor.getOriginalID());
                }
            }
            return orig;
        } else {
            return null;
        }
    }

    public ArrayList<String> getDeletedIDs() {
        return mDeletedIDs;
    }

    public ArrayList<UncachedSecretKey> getDeletedKeys() {
        return mDeletedKeys;
    }

    public List<Boolean> getNeedsSavingArray() {
        ArrayList<Boolean> mList = new ArrayList<Boolean>();
        for (int i = 0; i < mEditors.getChildCount(); ++i) {
            Editor editor = (Editor) mEditors.getChildAt(i);
            mList.add(editor.needsSaving());
        }
        return mList;
    }

    public List<Boolean> getNewIDFlags() {
        ArrayList<Boolean> mList = new ArrayList<Boolean>();
        for (int i = 0; i < mEditors.getChildCount(); ++i) {
            UserIdEditor editor = (UserIdEditor) mEditors.getChildAt(i);
            if (editor.isMainUserId()) {
                mList.add(0, editor.getIsNewID());
            } else {
                mList.add(editor.getIsNewID());
            }
        }
        return mList;
    }

    public List<Boolean> getNewKeysArray() {
        ArrayList<Boolean> mList = new ArrayList<Boolean>();
        if (mType == TYPE_KEY) {
            for (int i = 0; i < mEditors.getChildCount(); ++i) {
                KeyEditor editor = (KeyEditor) mEditors.getChildAt(i);
                mList.add(editor.getIsNewKey());
            }
        }
        return mList;
    }

    /**
     * {@inheritDoc}
     */
    public void onClick(View v) {
        if (mCanBeEdited) {
            switch (mType) {
                case TYPE_USER_ID: {
                    UserIdEditor view = (UserIdEditor) mInflater.inflate(
                            R.layout.edit_key_user_id_item, mEditors, false);
                    view.setEditorListener(this);
                    view.setValue("", mEditors.getChildCount() == 0, true);
                    mEditors.addView(view);
                    if (mEditorListener != null) {
                        mEditorListener.onEdited();
                    }
                    break;
                }

                case TYPE_KEY: {
                    CreateKeyDialogFragment mCreateKeyDialogFragment =
                            CreateKeyDialogFragment.newInstance(mEditors.getChildCount());
                    mCreateKeyDialogFragment
                            .setOnAlgorithmSelectedListener(
                                    new CreateKeyDialogFragment.OnAlgorithmSelectedListener() {
                                        @Override
                                        public void onAlgorithmSelected(Choice algorithmChoice, int keySize) {
                                            mNewKeyAlgorithmChoice = algorithmChoice;
                                            mNewKeySize = keySize;
                                            createKey();
                                        }
                                    });
                    mCreateKeyDialogFragment.show(mActivity.getSupportFragmentManager(), "createKeyDialog");
                    break;
                }

                default: {
                    break;
                }
            }
            this.updateEditorsVisible();
        }
    }

    public void setUserIds(Vector<String> list) {
        if (mType != TYPE_USER_ID) {
            return;
        }

        mEditors.removeAllViews();
        for (String userId : list) {
            UserIdEditor view = (UserIdEditor) mInflater.inflate(R.layout.edit_key_user_id_item,
                    mEditors, false);
            view.setEditorListener(this);
            view.setValue(userId, mEditors.getChildCount() == 0, false);
            view.setCanBeEdited(mCanBeEdited);
            mEditors.addView(view);
        }

        this.updateEditorsVisible();
    }

    public void setKeys(Vector<UncachedSecretKey> list, Vector<Integer> usages, boolean newKeys) {
        if (mType != TYPE_KEY) {
            return;
        }

        mEditors.removeAllViews();

        // go through all keys and set view based on them
        for (int i = 0; i < list.size(); i++) {
            KeyEditor view = (KeyEditor) mInflater.inflate(R.layout.edit_key_key_item, mEditors,
                    false);
            view.setEditorListener(this);
            boolean isMasterKey = (mEditors.getChildCount() == 0);
            view.setValue(list.get(i), isMasterKey, usages.get(i), newKeys);
            view.setCanBeEdited(mCanBeEdited);
            mEditors.addView(view);
        }

        this.updateEditorsVisible();
    }

    private void createKey() {

        // fill values for this action
        Boolean isMasterKey;

        String passphrase;
        if (mEditors.getChildCount() > 0) {
            UncachedSecretKey masterKey = ((KeyEditor) mEditors.getChildAt(0)).getValue();
            passphrase = PassphraseCacheService
                    .getCachedPassphrase(mActivity, masterKey.getKeyId());
            isMasterKey = false;
        } else {
            passphrase = "";
            isMasterKey = true;
        }
        /*
        data.putBoolean(KeychainIntentService.GENERATE_KEY_MASTER_KEY, isMasterKey);
        data.putString(KeychainIntentService.GENERATE_KEY_SYMMETRIC_PASSPHRASE, passphrase);
        data.putInt(KeychainIntentService.GENERATE_KEY_ALGORITHM, mNewKeyAlgorithmChoice.getId());
        data.putInt(KeychainIntentService.GENERATE_KEY_KEY_SIZE, mNewKeySize);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // show progress dialog
        mGeneratingDialog = ProgressDialogFragment.newInstance(
                getResources().getQuantityString(R.plurals.progress_generating, 1),
                ProgressDialog.STYLE_SPINNER,
                true,
                new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        mActivity.stopService(intent);
                    }
                });

        // Message is received after generating is done in KeychainIntentService
        KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(mActivity,
                mGeneratingDialog) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get new key from data bundle returned from service
                    Bundle data = message.getDataAsStringList();
                    UncachedSecretKey newKey = PgpConversionHelper
                            .BytesToPGPSecretKey(data
                                    .getByteArray(KeychainIntentService.RESULT_NEW_KEY));
                    addGeneratedKeyToView(newKey);
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        mGeneratingDialog.show(mActivity.getSupportFragmentManager(), "dialog");

        // start service with intent
        mActivity.startService(intent);
        */

    }

    private void addGeneratedKeyToView(UncachedSecretKey newKey) {
        // add view with new key
        KeyEditor view = (KeyEditor) mInflater.inflate(R.layout.edit_key_key_item,
                mEditors, false);
        view.setEditorListener(SectionView.this);
        int usage = 0;
        if (mEditors.getChildCount() == 0) {
            usage = UncachedSecretKey.CERTIFY_OTHER;
        }
        view.setValue(newKey, newKey.isMasterKey(), usage, true);
        mEditors.addView(view);
        SectionView.this.updateEditorsVisible();
        if (mEditorListener != null) {
            mEditorListener.onEdited();
        }
    }
}
