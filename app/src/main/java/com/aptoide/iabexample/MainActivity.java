package com.aptoide.iabexample;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import com.aptoide.iabexample.util.IabBroadcastReceiver;
import com.aptoide.iabexample.util.IabHelper;
import com.aptoide.iabexample.util.IabResult;
import com.aptoide.iabexample.util.Inventory;
import com.aptoide.iabexample.util.Purchase;
import com.asf.appcoins.sdk.iab.payment.PaymentDetails;
import com.asf.appcoins.sdk.iab.payment.PaymentStatus;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import java.util.ArrayList;
import java.util.List;

import static com.aptoide.iabexample.Application.appCoinsIab;

/**
 * Example game using in-app billing version 4.
 *
 * Before attempting to run this sample, please read the README file. It
 * contains important information on how to set up this project.
 *
 * All the game-specific logic is implemented here in MainActivity, while the
 * general-purpose boilerplate that can be reused in any app is provided in the
 * classes in the util/ subdirectory. When implementing your own application,
 * you can copy over util/*.java to make use of those utility classes.
 *
 * This game is a simple "driving" game where the player can buy gas
 * and drive. The car has a tank which stores gas. When the player purchases
 * gas, the tank fills up (1/4 tank at a time). When the player drives, the gas
 * in the tank diminishes (also 1/4 tank at a time).
 *
 * The user can also purchase a "premium upgrade" that gives them a red car
 * instead of the standard blue one (exciting!).
 *
 * The user can also purchase a subscription ("infinite gas") that allows them
 * to drive without using up any gas while that subscription is active.
 *
 * It's important to note the consumption mechanics for each item.
 *
 * PREMIUM: the item is purchased and NEVER consumed. So, after the original
 * purchase, the player will always own that item. The application knows to
 * display the red car instead of the blue one because it queries whether
 * the premium "item" is owned or not.
 *
 * INFINITE GAS: this is a subscription, and subscriptions can't be consumed.
 *
 * GAS: when gas is purchased, the "gas" item is then owned. We consume it
 * when we apply that item's effects to our app's world, which to us means
 * filling up 1/4 of the tank. This happens immediately after purchase!
 * It's at this point (and not when the user drives) that the "gas"
 * item is CONSUMED. Consumption should always happen when your game
 * world was safely updated to apply the effect of the purchase. So,
 * in an example scenario:
 *
 * BEFORE:      tank at 1/2
 * ON PURCHASE: tank at 1/2, "gas" item is owned
 * IMMEDIATELY: "gas" is consumed, tank goes to 3/4
 * AFTER:       tank at 3/4, "gas" item NOT owned any more
 *
 * Another important point to notice is that it may so happen that
 * the application crashed (or anything else happened) after the user
 * purchased the "gas" item, but before it was consumed. That's why,
 * on startup, we check if we own the "gas" item, and, if so,
 * we have to apply its effects to our world and consume it. This
 * is also very important!
 */
public class MainActivity extends Activity
    implements IabBroadcastReceiver.IabBroadcastListener, OnClickListener {
  // Debug tag, for logging
  static final String TAG = "TrivialDrive";
  // How many units (1/4 tank is our unit) fill in the tank.
  static final int TANK_MAX = 4;
  // Graphics for the gas gauge
  static int[] TANK_RES_IDS = {
      R.drawable.gas0, R.drawable.gas1, R.drawable.gas2, R.drawable.gas3, R.drawable.gas4
  };
  // Does the user have the premium upgrade?
  boolean mIsPremium = false;
  // Does the user have an active subscription to the infinite gas plan?
  boolean mSubscribedToInfiniteGas = false;
  // Will the subscription auto-renew?
  boolean mAutoRenewEnabled = false;
  // Tracks the currently owned infinite gas SKU, and the options in the Manage dialog
  String mInfiniteGasSku = "";
  String mFirstChoiceSku = "";
  String mSecondChoiceSku = "";
  // Used to select between purchasing gas on a monthly or yearly basis
  String mSelectedSubscriptionPeriod = "";
  // Current amount of gas in tank, in units
  int mTank;
  // The helper object
  IabHelper mHelper;
  // Provides purchase notification while this app is running
  IabBroadcastReceiver mBroadcastReceiver;

  // Listener that's called when we finish querying the items and subscriptions we own
  IabHelper.QueryInventoryFinishedListener mGotInventoryListener =
      new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
          Log.d(TAG, "Query inventory finished.");

          // Have we been disposed of in the meantime? If so, quit.
          if (mHelper == null) return;

          // Is it a failure?
          if (result.isFailure()) {
            complain("Failed to query inventory: " + result);
            return;
          }

          Log.d(TAG, "Query inventory was successful.");

          /*
           * Check for items we own. Notice that for each purchase, we check
           * the developer payload to see if it's correct! See
           * verifyDeveloperPayload().
           */

          // Do we have the premium upgrade?
          Purchase premiumPurchase = inventory.getPurchase(Skus.SKU_PREMIUM_ID);
          mIsPremium = (premiumPurchase != null && verifyDeveloperPayload(premiumPurchase));
          Log.d(TAG, "User is " + (mIsPremium ? "PREMIUM" : "NOT PREMIUM"));

          // First find out which subscription is auto renewing
          Purchase gasMonthly = inventory.getPurchase(Skus.SKU_INFINITE_GAS_MONTHLY_ID);
          Purchase gasYearly = inventory.getPurchase(Skus.SKU_INFINITE_GAS_YEARLY_ID);
          if (gasMonthly != null && gasMonthly.isAutoRenewing()) {
            mInfiniteGasSku = Skus.SKU_INFINITE_GAS_MONTHLY_ID;
            mAutoRenewEnabled = true;
          } else if (gasYearly != null && gasYearly.isAutoRenewing()) {
            mInfiniteGasSku = Skus.SKU_INFINITE_GAS_YEARLY_ID;
            mAutoRenewEnabled = true;
          } else {
            mInfiniteGasSku = "";
            mAutoRenewEnabled = false;
          }

          // The user is subscribed if either subscription exists, even if neither is auto
          // renewing
          mSubscribedToInfiniteGas = (gasMonthly != null && verifyDeveloperPayload(gasMonthly)) || (
              gasYearly != null
                  && verifyDeveloperPayload(gasYearly));
          Log.d(TAG, "User "
              + (mSubscribedToInfiniteGas ? "HAS" : "DOES NOT HAVE")
              + " infinite gas subscription.");
          if (mSubscribedToInfiniteGas) mTank = TANK_MAX;

          // Check for gas delivery -- if we own gas, we should fill up the tank immediately
          Purchase gasPurchase = inventory.getPurchase(Skus.SKU_GAS_ID);
          if (gasPurchase != null && verifyDeveloperPayload(gasPurchase)) {
            Log.d(TAG, "We have gas. Consuming it.");
            try {
              mHelper.consumeAsync(inventory.getPurchase(Skus.SKU_GAS_ID),
                  mConsumeFinishedListener);
            } catch (IabHelper.IabAsyncInProgressException e) {
              complain("Error consuming gas. Another async operation in progress.");
            }
            return;
          }

          updateUi();
          setWaitScreen(false);
          Log.d(TAG, "Initial inventory query finished; enabling main UI.");
        }
      };

  // Called when consumption is complete
  IabHelper.OnConsumeFinishedListener mConsumeFinishedListener =
      new IabHelper.OnConsumeFinishedListener() {
        public void onConsumeFinished(Purchase purchase, IabResult result) {
          Log.d(TAG, "Consumption finished. Purchase: " + purchase + ", result: " + result);

          // if we were disposed of in the meantime, quit.
          if (mHelper == null) return;

          // We know this is the "gas" sku because it's the only one we consume,
          // so we don't check which sku was consumed. If you have more than one
          // sku, you probably should check...
          if (result.isSuccess()) {
            // successfully consumed, so we apply the effects of the item in our
            // game world's logic, which in our case means filling the gas tank a bit
            Log.d(TAG, "Consumption successful. Provisioning.");
            mTank = mTank == TANK_MAX ? TANK_MAX : mTank + 1;
            saveData();
            alert("You filled 1/4 tank. Your tank is now " + String.valueOf(mTank) + "/4 full!");
          } else {
            complain("Error while consuming: " + result);
          }
          updateUi();
          setWaitScreen(false);
          Log.d(TAG, "End consumption flow.");
        }
      };

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    // load game data
    loadData();

    /* base64EncodedPublicKey should be YOUR APPLICATION'S PUBLIC KEY
     * (that you got from your Aptoide's back office). This is not your
     * developer public key, it's the *app-specific* public key.
     *
     * Instead of just storing the entire literal string here embedded in the
     * program,  construct the key at runtime from pieces or
     * use bit manipulation (for example, XOR with some other string) to hide
     * the actual key.  The key itself is not secret information, but we don't
     * want to make it easy for an attacker to replace the public key with one
     * of their own and then fake messages from the server.
     */
    String base64EncodedPublicKey = BuildConfig.IAB_KEY;

    // Some sanity checks to see if the developer (that's you!) really followed the
    // instructions to run this sample (don't put these checks on your app!)
    //if (base64EncodedPublicKey.contains("CONSTRUCT_YOUR")) {
    //  throw new RuntimeException(
    //      "Please put your app's public key in MainActivity.java. See README.");
    //}

    // Create the helper, passing it our context and the public key to verify signatures with
    Log.d(TAG, "Creating IAB helper.");
    mHelper = new IabHelper(this, base64EncodedPublicKey);

    // enable debug logging (for a production application, you should set this to false).
    mHelper.enableDebugLogging(true);

    // Start setup. This is asynchronous and the specified listener
    // will be called once setup completes.
    Log.d(TAG, "Starting setup.");
    mHelper.startSetup(result -> {
      Log.d(TAG, "Setup finished.");

      if (!result.isSuccess()) {
        // Oh noes, there was a problem.
        complain("Problem setting up in-app billing: " + result);
        return;
      }

      // Have we been disposed of in the meantime? If so, quit.
      if (mHelper == null) return;
    });

    updateUi();
  }

  @Override protected void onResume() {
    super.onResume();

    setWaitScreen(false);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    Log.d(TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data);
    super.onActivityResult(requestCode, resultCode, data);

    if (appCoinsIab.onActivityResult(requestCode, requestCode, data)) {
      final Disposable subscribe = appCoinsIab.getCurrentPayment()
          .distinctUntilChanged(PaymentDetails::getPaymentStatus)
          .take(1)
          .subscribe(paymentDetails -> runOnUiThread(() -> handlePayment(paymentDetails)));
    }
  }

  @Override public void onClick(DialogInterface dialog, int id) {
    if (id == 0 /* First choice item */) {
      mSelectedSubscriptionPeriod = mFirstChoiceSku;
    } else if (id == 1 /* Second choice item */) {
      mSelectedSubscriptionPeriod = mSecondChoiceSku;
    } else if (id == DialogInterface.BUTTON_POSITIVE /* continue button */) {

      if (TextUtils.isEmpty(mSelectedSubscriptionPeriod)) {
        // The user has not changed from the default selection
        mSelectedSubscriptionPeriod = mFirstChoiceSku;
      }

      List<String> oldSkus;
      if (!TextUtils.isEmpty(mInfiniteGasSku) && !mInfiniteGasSku.equals(
          mSelectedSubscriptionPeriod)) {
        // The user currently has a valid subscription, any purchase action is going to
        // replace that subscription
        oldSkus = new ArrayList<>();
        oldSkus.add(mInfiniteGasSku);
      }

      setWaitScreen(true);
      Log.d(TAG, "Launching purchase flow for gas subscription.");
      // TODO: 14-03-2018 neuro subscription
      // Reset the dialog options
      mSelectedSubscriptionPeriod = "";
      mFirstChoiceSku = "";
      mSecondChoiceSku = "";
    } else if (id != DialogInterface.BUTTON_NEGATIVE) {
      // There are only four buttons, this should not happen
      Log.e(TAG, "Unknown button clicked in subscription dialog: " + id);
    }
  }

  @Override public void receivedBroadcast() {
    // Received a broadcast notification that the inventory of items has changed
    Log.d(TAG, "Received broadcast notification. Querying inventory.");
    try {
      mHelper.queryInventoryAsync(mGotInventoryListener);
    } catch (IabHelper.IabAsyncInProgressException e) {
      complain("Error querying inventory. Another async operation in progress.");
    }
  }

  private void handlePayment(PaymentDetails paymentDetails) {
    if (paymentDetails.getPaymentStatus() == PaymentStatus.PENDING
        || paymentDetails.getPaymentStatus() == PaymentStatus.SUCCESS) {
      String skuId = paymentDetails.getSkuId();
      appCoinsIab.consume(skuId);

      // successfully consumed, so we apply the effects of the item in our
      // game world's logic, which in our case means filling the gas tank a bit
      if (Skus.SKU_GAS_ID.equals(skuId)) {
        Log.d(TAG, "Consumption successful. Provisioning.");
        mTank = mTank == TANK_MAX ? TANK_MAX : mTank + 1;
        saveData();
        alert("You filled 1/4 tank. Your tank is now " + String.valueOf(mTank) + "/4 full!");
      } else {
        if (Skus.SKU_PREMIUM_ID.equals(skuId)) {
          // bought the premium upgrade!
          Log.d(TAG, "Purchase is premium upgrade. Congratulating user.");
          alert("Thank you for upgrading to premium!");
          mIsPremium = true;
          saveData();
        }
      }

      updateUi();
      Log.d(TAG, "End consumption flow.");
    }
    setWaitScreen(false);
  }

  // User clicked the "Buy Gas" button
  public void onBuyGasButtonClicked(View arg0) {
    Log.d(TAG, "Buy gas button clicked.");

    if (mSubscribedToInfiniteGas) {
      complain("No need! You're subscribed to infinite gas. Isn't that awesome?");
      return;
    }

    if (mTank >= TANK_MAX) {
      complain("Your tank is full. Drive around a bit!");
      return;
    }

    // launch the gas purchase UI flow.
    // We will be notified of completion via mPurchaseFinishedListener
    setWaitScreen(true);
    Log.d(TAG, "Launching purchase flow for gas.");

    startBuyFlow(Skus.SKU_GAS_ID);
  }

  // User clicked the "Upgrade to Premium" button.
  public void onUpgradeAppButtonClicked(View arg0) {
    Log.d(TAG, "Upgrade button clicked; launching purchase flow for upgrade.");
    setWaitScreen(true);

    startBuyFlow(Skus.SKU_PREMIUM_ID);
  }

  @NonNull private Disposable startBuyFlow(String skuPremiumId) {
    return appCoinsIab.buy(skuPremiumId, this)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {
          // Buy process successfully started.
          setWaitScreen(false);
        }, (throwable) -> {
          // User didn't install wallet
          throwable.printStackTrace();
          setWaitScreen(false);
        });
  }

  // "Subscribe to infinite gas" button clicked. Explain to user, then start purchase
  // flow for subscription.
  public void onInfiniteGasButtonClicked(View arg0) {
    if (true) {
      Toast.makeText(this, "Not Implemented", Toast.LENGTH_SHORT)
          .show();
      return;
    }

    CharSequence[] options;
    if (!mSubscribedToInfiniteGas || !mAutoRenewEnabled) {
      // Both subscription options should be available
      options = new CharSequence[2];
      options[0] = getString(R.string.subscription_period_monthly);
      options[1] = getString(R.string.subscription_period_yearly);
      mFirstChoiceSku = Skus.SKU_INFINITE_GAS_MONTHLY_ID;
      mSecondChoiceSku = Skus.SKU_INFINITE_GAS_YEARLY_ID;
    } else {
      // This is the subscription upgrade/downgrade path, so only one option is valid
      options = new CharSequence[1];
      if (mInfiniteGasSku.equals(Skus.SKU_INFINITE_GAS_MONTHLY_ID)) {
        // Give the option to upgrade to yearly
        options[0] = getString(R.string.subscription_period_yearly);
        mFirstChoiceSku = Skus.SKU_INFINITE_GAS_YEARLY_ID;
      } else {
        // Give the option to downgrade to monthly
        options[0] = getString(R.string.subscription_period_monthly);
        mFirstChoiceSku = Skus.SKU_INFINITE_GAS_MONTHLY_ID;
      }
      mSecondChoiceSku = "";
    }

    int titleResId;
    if (!mSubscribedToInfiniteGas) {
      titleResId = R.string.subscription_period_prompt;
    } else if (!mAutoRenewEnabled) {
      titleResId = R.string.subscription_resignup_prompt;
    } else {
      titleResId = R.string.subscription_update_prompt;
    }

    Builder builder = new Builder(this);
    builder.setTitle(titleResId)
        .setSingleChoiceItems(options, 0 /* checkedItem */, this)
        .setPositiveButton(R.string.subscription_prompt_continue, this)
        .setNegativeButton(R.string.subscription_prompt_cancel, this);
    AlertDialog dialog = builder.create();
    dialog.show();
  }

  // Drive button clicked. Burn gas!
  public void onDriveButtonClicked(View arg0) {
    Log.d(TAG, "Drive button clicked.");
    if (!mSubscribedToInfiniteGas && mTank <= 0) {
      alert("Oh, no! You are out of gas! Try buying some!");
    } else {
      if (!mSubscribedToInfiniteGas) --mTank;
      saveData();
      alert("Vroooom, you drove a few miles.");
      updateUi();
      Log.d(TAG, "Vrooom. Tank is now " + mTank);
    }
  }

  // updates UI to reflect model
  public void updateUi() {
    // update the car color to reflect premium status or lack thereof
    ((ImageView) findViewById(R.id.free_or_premium)).setImageResource(
        mIsPremium ? R.drawable.premium : R.drawable.free);

    // "Upgrade" button is only visible if the user is not premium
    findViewById(R.id.upgrade_button).setVisibility(mIsPremium ? View.GONE : View.VISIBLE);

    ImageView infiniteGasButton = (ImageView) findViewById(R.id.infinite_gas_button);
    if (mSubscribedToInfiniteGas) {
      // If subscription is active, show "Manage Infinite Gas"
      infiniteGasButton.setImageResource(R.drawable.manage_infinite_gas);
    } else {
      // The user does not have infinite gas, show "Get Infinite Gas"
      infiniteGasButton.setImageResource(R.drawable.get_infinite_gas);
    }

    // update gas gauge to reflect tank status
    if (mSubscribedToInfiniteGas) {
      ((ImageView) findViewById(R.id.gas_gauge)).setImageResource(R.drawable.gas_inf);
    } else {
      int index = mTank >= TANK_RES_IDS.length ? TANK_RES_IDS.length - 1 : mTank;
      ((ImageView) findViewById(R.id.gas_gauge)).setImageResource(TANK_RES_IDS[index]);
    }
  }

  // Enables or disables the "please wait" screen.
  void setWaitScreen(boolean set) {
    findViewById(R.id.screen_main).setVisibility(set ? View.GONE : View.VISIBLE);
    findViewById(R.id.screen_wait).setVisibility(set ? View.VISIBLE : View.GONE);
  }

  void complain(String message) {
    Log.e(TAG, "**** TrivialDrive Error: " + message);
    alert("Error: " + message);
  }

  void alert(String message) {
    AlertDialog.Builder bld = new AlertDialog.Builder(this);
    bld.setMessage(message);
    bld.setNeutralButton("OK", null);
    Log.d(TAG, "Showing alert dialog: " + message);
    bld.create()
        .show();
  }

  void saveData() {
    SharedPreferences.Editor spe = getPreferences(MODE_PRIVATE).edit();
    spe.putBoolean("mIsPremium", mIsPremium);
    spe.putInt("tank", mTank);
    spe.apply();
    Log.d(TAG, "Saved data: tank = " + String.valueOf(mTank));
  }

  void loadData() {
    SharedPreferences sp = getPreferences(MODE_PRIVATE);
    mTank = sp.getInt("tank", 2);
    mIsPremium = sp.getBoolean("mIsPremium", mIsPremium);
    Log.d(TAG, "Loaded data: tank = " + String.valueOf(mTank));
  }

  /** Verifies the developer payload of a purchase. */
  boolean verifyDeveloperPayload(Purchase p) {
    String payload = p.getDeveloperPayload();

    /*
     * TODO: verify that the developer payload of the purchase is correct. It will be
     * the same one that you sent when initiating the purchase.
     *
     * WARNING: Locally generating a random string when starting a purchase and
     * verifying it here might seem like a good approach, but this will fail in the
     * case where the user purchases an item on one device and then uses your app on
     * a different device, because on the other device you will not have access to the
     * random string you originally generated.
     *
     * So a good developer payload has these characteristics:
     *
     * 1. If two different users purchase an item, the payload is different between them,
     *    so that one user's purchase can't be replayed to another user.
     *
     * 2. The payload must be such that you can verify it even when the app wasn't the
     *    one who initiated the purchase flow (so that items purchased by the user on
     *    one device work on other devices owned by the user).
     *
     * Using your own server to store and verify developer payloads across app
     * installations is recommended.
     */

    return true;
  }
}
