package fr.free.nrw.commons.nearby;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.location.LocationServiceManager;
import fr.free.nrw.commons.location.LocationUpdateListener;
import fr.free.nrw.commons.theme.NavigationBaseActivity;
import fr.free.nrw.commons.utils.UriSerializer;
import fr.free.nrw.commons.utils.ViewUtil;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;


public class NearbyActivity extends NavigationBaseActivity implements LocationUpdateListener {

    private static final int LOCATION_REQUEST = 1;

    @BindView(R.id.progressBar)
    ProgressBar progressBar;

    @BindView(R.id.bottom_sheet)
    LinearLayout bottomSheet;
    @BindView(R.id.bottom_sheet_details)
    LinearLayout bottomSheetDetails;
    @BindView(R.id.transparentView)
    View transparentView;

    @Inject
    LocationServiceManager locationManager;
    @Inject
    NearbyController nearbyController;

    private LatLng curLatLang;
    private Bundle bundle;
    private Disposable placesDisposable;
    private boolean lockNearbyView; //Determines if the nearby places needs to be refreshed
    private BottomSheetBehavior bottomSheetBehavior; // Behavior for list bottom sheet
    private BottomSheetBehavior bottomSheetBehaviorForDetails; // Behavior for details bottom sheet
    private NearbyMapFragment nearbyMapFragment;
    private static final String TAG_RETAINED_FRAGMENT = "RetainedFragment";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby);
        ButterKnife.bind(this);
        resumeFragment();
        bundle = new Bundle();

        initBottomSheetBehaviour();
        initDrawer();
    }

    private void resumeFragment() {
        // find the retained fragment on activity restarts
        android.support.v4.app.FragmentManager fm = getSupportFragmentManager();
        nearbyMapFragment = (NearbyMapFragment) fm.findFragmentByTag(TAG_RETAINED_FRAGMENT);

        // create the fragment and data the first time
        if (nearbyMapFragment == null) {
            // add the fragment
            nearbyMapFragment = new NearbyMapFragment();
            fm.beginTransaction().add(nearbyMapFragment, TAG_RETAINED_FRAGMENT).commit();
            // load data from a data source or perform any calculation
        }

    }

    private void initBottomSheetBehaviour() {
        transparentView.setAlpha(0);

        bottomSheet.getLayoutParams().height = getWindowManager()
                .getDefaultDisplay().getHeight() / 16 * 9;
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        // TODO initProperBottomSheetBehavior();
        bottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {

            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                prepareViewsForSheetPosition(newState);
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {

            }
        });

        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        bottomSheetBehaviorForDetails = BottomSheetBehavior.from(bottomSheetDetails);
        bottomSheetBehaviorForDetails.setState(BottomSheetBehavior.STATE_HIDDEN);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_nearby, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_refresh:
                lockNearbyView(false);
                refreshView(true,
                        LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
                return true;
            case R.id.action_display_list:
                bottomSheetBehaviorForDetails.setState(BottomSheetBehavior.STATE_HIDDEN);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void requestLocationPermissions() {
        if (!isFinishing()) {
            locationManager.requestPermissions(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case LOCATION_REQUEST: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    refreshView(false,
                            LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
                } else {
                    //If permission not granted, go to page that says Nearby Places cannot be displayed
                    hideProgressBar();
                    showLocationPermissionDeniedErrorDialog();
                }
            }
        }
    }

    private void showLocationPermissionDeniedErrorDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.nearby_needs_permissions)
                .setCancelable(false)
                .setPositiveButton(R.string.give_permission, (dialog, which) -> {
                    //will ask for the location permission again
                    checkGps();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    //dismiss dialog and finish activity
                    dialog.cancel();
                    finish();
                })
                .create()
                .show();
    }

    private void checkGps() {
        if (!locationManager.isProviderEnabled()) {
            Timber.d("GPS is not enabled");
            new AlertDialog.Builder(this)
                    .setMessage(R.string.gps_disabled)
                    .setCancelable(false)
                    .setPositiveButton(R.string.enable_gps,
                            (dialog, id) -> {
                                Intent callGPSSettingIntent = new Intent(
                                        android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                Timber.d("Loaded settings page");
                                startActivityForResult(callGPSSettingIntent, 1);
                            })
                    .setNegativeButton(R.string.menu_cancel_upload, (dialog, id) -> {
                        showLocationPermissionDeniedErrorDialog();
                        dialog.cancel();
                    })
                    .create()
                    .show();
        } else {
            Timber.d("GPS is enabled");
            checkLocationPermission();
        }
    }

    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (locationManager.isLocationPermissionGranted()) {
                refreshView(false,
                        LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
            } else {
                // Should we show an explanation?
                if (locationManager.isPermissionExplanationRequired(this)) {
                    // Show an explanation to the user *asynchronously* -- don't block
                    // this thread waiting for the user's response! After the user
                    // sees the explanation, try again to request the permission.
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.location_permission_rationale_nearby))
                            .setPositiveButton("OK", (dialog, which) -> {
                                requestLocationPermissions();
                                dialog.dismiss();
                            })
                            .setNegativeButton("Cancel", (dialog, id) -> {
                                showLocationPermissionDeniedErrorDialog();
                                dialog.cancel();
                            })
                            .create()
                            .show();

                } else {
                    // No explanation needed, we can request the permission.
                    requestLocationPermissions();
                }
            }
        } else {
            refreshView(false,
                    LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            Timber.d("User is back from Settings page");
            refreshView(false,
                    LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        locationManager.addLocationListener(this);
        locationManager.registerLocationManager();
    }

    @Override
    protected void onStop() {
        super.onStop();
        locationManager.removeLocationListener(this);
        locationManager.unregisterLocationManager();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (placesDisposable != null) {
            placesDisposable.dispose();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lockNearbyView = false;
        checkGps();
    }

    @Override
    public void onPause() {
        super.onPause();
        // this means that this activity will not be recreated now, user is leaving it
        // or the activity is otherwise finishing
        if(isFinishing()) {
            android.support.v4.app.FragmentManager fm = getSupportFragmentManager();
            // we will not need this fragment anymore, this may also be a good place to signal
            // to the retained fragment object to perform its own cleanup.
            fm.beginTransaction().remove(nearbyMapFragment).commit();
        }
    }



    /**
     * This method should be the single point to load/refresh nearby places
     *
     * @param isHardRefresh
     * @param locationChangeType defines if location shanged significantly or slightly
     */
    private void refreshView(boolean isHardRefresh,
                             LocationServiceManager.LocationChangeType locationChangeType) {
        if (lockNearbyView) {
            return;
        }
        locationManager.registerLocationManager();
        LatLng lastLocation = locationManager.getLastLocation();
        if (curLatLang != null && curLatLang.equals(lastLocation)) { //refresh view only if location has changed
            if (isHardRefresh) {
                ViewUtil.showLongToast(this, R.string.nearby_location_has_not_changed);
            }
            return;
        }
        curLatLang = lastLocation;

        if (curLatLang == null) {
            Timber.d("Skipping update of nearby places as location is unavailable");
            return;
        }

        if (locationChangeType
                .equals(LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED)) {
            progressBar.setVisibility(View.VISIBLE);
            placesDisposable = Observable.fromCallable(() -> nearbyController
                    .loadAttractionsFromLocation(curLatLang))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::populatePlaces);
        } else if (locationChangeType
                .equals(LocationServiceManager.LocationChangeType.LOCATION_SLIGHTLY_CHANGED)) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(Uri.class, new UriSerializer())
                    .create();
            String gsonCurLatLng = gson.toJson(curLatLang);
            bundle.putString("CurLatLng", gsonCurLatLng);
            updateMapFragment(true);
        }

    }

    //private void populatePlaces(List<Place> placeList) {
    private void populatePlaces(NearbyController.NearbyPlacesInfo nearbyPlacesInfo) {
        List<Place> placeList = nearbyPlacesInfo.placeList;
        LatLng[] boundaryCoordinates = nearbyPlacesInfo.boundaryCoordinates;
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Uri.class, new UriSerializer())
                .create();
        String gsonPlaceList = gson.toJson(placeList);
        String gsonCurLatLng = gson.toJson(curLatLang);
        String gsonBoundaryCoordinates = gson.toJson(boundaryCoordinates);

        if (placeList.size() == 0) {
            int duration = Toast.LENGTH_SHORT;
            Toast toast = Toast.makeText(this, R.string.no_nearby, duration);
            toast.show();
        }

        bundle.clear();
        bundle.putString("PlaceList", gsonPlaceList);
        bundle.putString("CurLatLng", gsonCurLatLng);
        bundle.putString("BoundaryCoord", gsonBoundaryCoordinates);

        // First time to init fragments
        if (getMapFragment() == null){
            lockNearbyView(true);
            setMapFragment();
            setListFragment();

            hideProgressBar();
            lockNearbyView(false);
        } else { // There are fragments, just update the map and list
            updateMapFragment(false);
        }

    }

    private void lockNearbyView(boolean lock) {
        if (lock) {
            lockNearbyView = true;
            locationManager.unregisterLocationManager();
            locationManager.removeLocationListener(this);
        } else {
            lockNearbyView = false;
            locationManager.registerLocationManager();
            locationManager.addLocationListener(this);
        }
    }

    private void hideProgressBar() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    private NearbyMapFragment getMapFragment() {
        return (NearbyMapFragment) getSupportFragmentManager().findFragmentByTag("NearbyMapFragment");
    }

    private void updateMapFragment(boolean isSlightUpdate) {
        /*
        * Significant update means updating nearby place markers. Slightly update means only
        * updating current location marker and camera target.
        * We update our map Significantly on each 1000 meter change, but we can't never know
        * the frequency of nearby places. Thus we check if we are close to the boundaries of
        * our nearby markers, we update our map Significantly.
        * */

        NearbyMapFragment nearbyMapFragment = getMapFragment();
        if (nearbyMapFragment != null) {
            // TODO: buradasın eger sınırlara yakınsan significant update yap ve methodların adlarını değiştir.
            /*
            * If we are close to nearby places boundaries, we need a significant update to
            * get new nearby places. Check order is south, north, west, east
            * */
            hideProgressBar(); // In case it is visible (this happens, not an impossible case)

            if (curLatLang.getLatitude() <= nearbyMapFragment.boundaryCoordinates[0].getLatitude()
                    || curLatLang.getLatitude() >= nearbyMapFragment.boundaryCoordinates[1].getLatitude()
                    || curLatLang.getLongitude() <= nearbyMapFragment.boundaryCoordinates[2].getLongitude()
                    || curLatLang.getLongitude() >= nearbyMapFragment.boundaryCoordinates[3].getLongitude()) {
                // populate places
                placesDisposable = Observable.fromCallable(() -> nearbyController
                        .loadAttractionsFromLocation(curLatLang))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::populatePlaces);
                nearbyMapFragment.setArguments(bundle);
                nearbyMapFragment.updateMapSignificantly();
                return;
            }

            if (isSlightUpdate) {
                nearbyMapFragment.setArguments(bundle);
                nearbyMapFragment.updateMapSlightly();
            } else {
                nearbyMapFragment.setArguments(bundle);
                nearbyMapFragment.updateMapSignificantly();
            }
        } else {
            lockNearbyView(true);
            setMapFragment();
            setListFragment();

            hideProgressBar();
            lockNearbyView(false);
        }
    }

    /**
     * Calls fragment for map view.
     */
    private void setMapFragment() {
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        Fragment fragment = new NearbyMapFragment();
        fragment.setArguments(bundle);
        fragmentTransaction.replace(R.id.container, fragment, fragment.getClass().getSimpleName());
        fragmentTransaction.commitAllowingStateLoss();
    }

    /**
     * Calls fragment for list view.
     */
    private void setListFragment() {
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        Fragment fragment = new NearbyListFragment();
        fragment.setArguments(bundle);
        fragmentTransaction.replace(R.id.container_sheet, fragment, fragment.getClass().getSimpleName());
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        fragmentTransaction.commitAllowingStateLoss();
    }

    @Override
    public void onLocationChangedSignificantly(LatLng latLng) {
        Toast.makeText(this, "onLocationChangedSignificantly",
                Toast.LENGTH_LONG).show();
        refreshView(false,
                LocationServiceManager.LocationChangeType.LOCATION_SIGNIFICANTLY_CHANGED);
    }

    @Override
    public void onLocationChangedSlightly(LatLng latLng) {
        Toast.makeText(this, "onLocationChangedSlightly",
                Toast.LENGTH_LONG).show();
        refreshView(false,
                LocationServiceManager.LocationChangeType.LOCATION_SLIGHTLY_CHANGED);
    }

    public void prepareViewsForSheetPosition(int bottomSheetState) {
        // TODO
    }

}
