package com.fultograph.app.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.fultograph.app.R;
import com.fultograph.app.databinding.ActivityMainBinding;

/**
 * Main Activity host for Fultograph SSTV application.
 * Manages clean navigation between Transmit and Receive fragments via BottomNavigationView.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final Fragment transmitFragment = new TransmitFragment();
    private final Fragment receiveFragment = new ReceiveFragment();
    private Fragment activeFragment = transmitFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup fragment container with both fragments added, showing Transmit by default
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, receiveFragment, "RECEIVE").hide(receiveFragment)
                .add(R.id.fragment_container, transmitFragment, "TRANSMIT")
                .commit();

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_transmit) {
                if (activeFragment != transmitFragment) {
                    getSupportFragmentManager().beginTransaction()
                            .hide(activeFragment)
                            .show(transmitFragment)
                            .commit();
                    activeFragment = transmitFragment;
                }
                return true;
            } else if (itemId == R.id.navigation_receive) {
                if (activeFragment != receiveFragment) {
                    getSupportFragmentManager().beginTransaction()
                            .hide(activeFragment)
                            .show(receiveFragment)
                            .commit();
                    activeFragment = receiveFragment;
                }
                return true;
            }
            return false;
        });
    }
}
