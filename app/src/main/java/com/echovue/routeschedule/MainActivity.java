package com.echovue.routeschedule;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.LocationRequest;
import com.tomtom.online.sdk.common.location.LatLng;
import com.tomtom.online.sdk.common.permission.AndroidPermissionChecker;
import com.tomtom.online.sdk.common.permission.PermissionChecker;
import com.tomtom.online.sdk.location.LocationSource;
import com.tomtom.online.sdk.location.LocationSourceFactory;
import com.tomtom.online.sdk.location.LocationUpdateListener;
import com.tomtom.online.sdk.map.MapFragment;
import com.tomtom.online.sdk.map.OnMapReadyCallback;
import com.tomtom.online.sdk.map.RouteBuilder;
import com.tomtom.online.sdk.map.TomtomMap;
import com.tomtom.online.sdk.routing.OnlineRoutingApi;
import com.tomtom.online.sdk.routing.RoutingApi;
import com.tomtom.online.sdk.routing.data.FullRoute;
import com.tomtom.online.sdk.routing.data.RouteQuery;
import com.tomtom.online.sdk.routing.data.RouteQueryBuilder;
import com.tomtom.online.sdk.routing.data.RouteResponse;
import com.tomtom.online.sdk.routing.data.RouteType;
import com.tomtom.online.sdk.routing.data.TravelMode;
import com.tomtom.online.sdk.search.OnlineSearchApi;
import com.tomtom.online.sdk.search.SearchApi;
import com.tomtom.online.sdk.search.data.fuzzy.FuzzySearchQueryBuilder;
import com.tomtom.online.sdk.search.data.fuzzy.FuzzySearchResponse;
import com.tomtom.online.sdk.search.data.fuzzy.FuzzySearchResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.observers.DisposableSingleObserver;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity implements LocationUpdateListener, OnMapReadyCallback {

    private AutoCompleteTextView txtAddress;
    private TextView txtDepartureTime;

    private Button btnScheduleDelivery;
    private SearchApi searchApi;
    private RoutingApi routingApi;

    private List<String> addressAutocompleteList;
    private Map<String, LatLng> searchResultsMap;
    private ArrayAdapter<String> searchAdapter;
    private Handler searchTimerHandler = new Handler();
    private Runnable searchRunnable;
    private LocationSource locationSource;
    private LatLng currentLocation;
    private DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm");

    private TomtomMap tomtomMap;
    private TravelMode travelMode;
    private LatLng destination;

    private static final int MIN_LEVEL_OF_FUZZINESS = 2;
    private static final int MIN_AUTOCOMPLETE_CHARACTERS = 3;
    private static final int AUTOCOMPLETE_SEARCH_DELAY_MILLIS = 600;
    private static final int PERMISSION_REQUEST_LOCATION = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtAddress = findViewById(R.id.txtAddress);
        txtDepartureTime = findViewById(R.id.txtTime);
        btnScheduleDelivery = findViewById(R.id.btnScheduleDelivery);

        initTomTomAPIs();
        initCurrentLocation();
        travelMode = TravelMode.TRUCK;
        destination = currentLocation;

        configureAutocomplete(txtAddress);

        txtAddress.setAdapter(searchAdapter);
        btnScheduleDelivery.setOnClickListener(v -> {
            String time[] =  txtDepartureTime.getText().toString().split(":");
            LocalDateTime deliveryTime = LocalDate.now().atTime(new Integer(time[0]), new Integer(time[1]));
            requestRoute(currentLocation, destination, travelMode, Date.from(deliveryTime.atZone(ZoneId.systemDefault()).toInstant()));
            hideKeyboard(v);
            tomtomMap.zoomToAllMarkers();
        });
    }

    private void initTomTomAPIs() {
        searchApi = OnlineSearchApi.create(getApplicationContext());
        routingApi = OnlineRoutingApi.create(getApplicationContext());
        routingApi = OnlineRoutingApi.create(getApplicationContext());
        MapFragment mapFragment = (MapFragment) getSupportFragmentManager().findFragmentById(R.id.mapFragment);
        mapFragment.getAsyncMap(this);
    }

    private void configureAutocomplete(final AutoCompleteTextView autoCompleteTextView) {
        addressAutocompleteList = new ArrayList<>();
        searchResultsMap = new HashMap<>();
        searchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, addressAutocompleteList);

        autoCompleteTextView.setAdapter(searchAdapter);
        autoCompleteTextView.addTextChangedListener(new BaseTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchTimerHandler != null) {
                    searchTimerHandler.removeCallbacks(searchRunnable);
                }
            }

            @Override
            public void afterTextChanged(final Editable s) {
                if (s.length() > 0) {
                    if (s.length() >= MIN_AUTOCOMPLETE_CHARACTERS) {
                        searchRunnable = () -> addressAutoComplete(s.toString());
                        searchAdapter.clear();
                        searchTimerHandler.postDelayed(searchRunnable, AUTOCOMPLETE_SEARCH_DELAY_MILLIS);
                    }
                }
            }
        });
        autoCompleteTextView.setOnItemClickListener((parent, view, position, id) -> {
            String item = (String) parent.getItemAtPosition(position);
            if (autoCompleteTextView == txtAddress) {
                destination = searchResultsMap.get(item);
            } else if (autoCompleteTextView == txtAddress) {
                destination = searchResultsMap.get(item);
            }
            hideKeyboard(view);
        });
    }

    private void addressAutoComplete(final String address) {
        searchApi.search(new FuzzySearchQueryBuilder(address)
            .withLanguage(Locale.getDefault().toLanguageTag())
            .withTypeAhead(true)
            .withMinFuzzyLevel(MIN_LEVEL_OF_FUZZINESS).build()
        )
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new DisposableSingleObserver<FuzzySearchResponse>() {
            @Override
            public void onSuccess(FuzzySearchResponse fuzzySearchResponse) {
                if (!fuzzySearchResponse.getResults().isEmpty()) {
                    addressAutocompleteList.clear();
                    searchResultsMap.clear();
                    for (FuzzySearchResult result : fuzzySearchResponse.getResults()) {
                        String addressString = result.getAddress().getFreeformAddress();
                        addressAutocompleteList.add(addressString);
                        searchResultsMap.put(addressString, result.getPosition());
                    }
                    searchAdapter.clear();
                    searchAdapter.addAll(addressAutocompleteList);
                    searchAdapter.getFilter().filter("");
                }
            }

            @Override
            public void onError(Throwable e) {
                Toast.makeText(MainActivity.this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initCurrentLocation() {
        PermissionChecker permissionChecker = AndroidPermissionChecker.createLocationChecker(this);
        if(permissionChecker.ifNotAllPermissionGranted()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_LOCATION);
        }
        LocationSourceFactory locationSourceFactory = new LocationSourceFactory();
        locationSource = locationSourceFactory.createDefaultLocationSource(this, this,  LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setFastestInterval(2000)
                .setInterval(5000));
        locationSource.activate();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_LOCATION:
                if(grantResults.length >= 2 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    locationSource.activate();
                }
                else {
                    Toast.makeText(this, "Location permissions not granted.", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    @Override
    public void onLocationChanged(final Location location) {
        currentLocation = new LatLng(location);
    }

    private void requestRoute(final LatLng departure, final LatLng destination, TravelMode byWhat, Date arriveAt) {
        RouteQuery routeQuery = new RouteQueryBuilder(departure, destination)
                .withRouteType(RouteType.FASTEST)
                .withConsiderTraffic(true)
                .withTravelMode(byWhat)
                .withArriveAt(arriveAt)
                .build();

        routingApi.planRoute(routeQuery)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableSingleObserver<RouteResponse>() {
                    @Override
                    public void onSuccess(RouteResponse routeResponse) {
                        if (routeResponse.hasResults()) {
                            FullRoute fullRoute = routeResponse.getRoutes().get(0);
                            int currentTravelTime = fullRoute.getSummary().getTravelTimeInSeconds();
                            LocalDateTime departureTime = arriveAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().minusSeconds(currentTravelTime);
                            Toast.makeText(getApplicationContext(), "Depart at " + departureTime.format(timeFormatter), Toast.LENGTH_LONG).show();
                            displayRouteOnMap(fullRoute.getCoordinates());
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        Toast.makeText(getApplicationContext(), "Error finding the route.", Toast.LENGTH_LONG).show();
                    }

                    private void displayRouteOnMap(List<LatLng> coordinates) {
                        RouteBuilder routeBuilder = new RouteBuilder(coordinates)
                                .isActive(true);
                        tomtomMap.clear();
                        tomtomMap.addRoute(routeBuilder);
                        tomtomMap.displayRoutesOverview();
                    }
                });
    }

    @Override
    public void onMapReady(@NonNull TomtomMap tomtomMap) {
        this.tomtomMap = tomtomMap;
        this.tomtomMap.setMyLocationEnabled(true);
        this.tomtomMap.clear();
    }

    private void hideKeyboard(View view) {
        InputMethodManager in = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (in != null) {
            in.hideSoftInputFromWindow(view.getApplicationWindowToken(), 0);
        }
    }

    private abstract class BaseTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    }
}
