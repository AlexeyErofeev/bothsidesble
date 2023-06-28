package com.healbe.bothsidesble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.client_fragment.*
import kotlinx.android.synthetic.main.server_fragment.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val notGranted = arrayListOf<String>()
    private val pagerAdapter by lazy { PagerAdapter(supportFragmentManager, this) }
    
    companion object {
        private const val REQUEST_PERMISSIONS: Int = 1234
    }
    
    @SuppressLint("Visibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        notSup.visibility = View.GONE
        pager.adapter = pagerAdapter
        
        permissions()
    }
    
    private fun permissions() {
        arrayListOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
            .forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("MainActivity", "$it not granted")
                    notGranted.add(it)
                } else Log.d("MainActivity", "$it granted")
            }
        
        if (notGranted.isNotEmpty())
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQUEST_PERMISSIONS)
        else initBluetooth()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.any { it != PackageManager.PERMISSION_GRANTED })
                    permissions()
                else
                    initBluetooth()
            }
        }
    }
    
    @SuppressLint("Visibility")
    private fun initBluetooth() {
        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            notSup.visibility = View.VISIBLE
            return
        }
        
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)
        
        if (!bluetoothAdapter.isEnabled) {
            notSup.visibility = View.VISIBLE
            notSup.text = getString(R.string.turning)
            bluetoothAdapter.enable()
        } else pagerAdapter.fill()
    }
    
    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("Visibility")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                BluetoothAdapter.STATE_ON -> {
                    notSup.visibility = View.GONE
                    pager.adapter = pagerAdapter
                    pagerAdapter.fill()
                }
                
                BluetoothAdapter.STATE_OFF -> {
                    pagerAdapter.clear()
                    pager.adapter = null
                    notSup.visibility = View.VISIBLE
                    notSup.text = getString(R.string.turn)
                }
            }
        }
    }
    
    private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {
        if (bluetoothAdapter == null) {
            Log.w("MainActivity", "Bluetooth is not supported")
            return false
        }
        
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w("MainActivity", "Bluetooth LE is not supported")
            return false
        }
        
        return true
    }
}

class PagerAdapter(fragmentManager: FragmentManager, context: Context) :
    FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
    private val fragments = mutableListOf<Fragment>()
    private val titles = mutableListOf(context.getString(R.string.server), context.getString(R.string.client))
    
    fun clear() {
        fragments.clear()
        notifyDataSetChanged()
    }
    
    fun fill() {
        clear()
        fragments.add(ServerFragment())
        fragments.add(ClientFragment())
        notifyDataSetChanged()
    }
    
    override fun getItem(position: Int): Fragment {
        return fragments[position]
    }
    
    override fun getCount(): Int = fragments.size
    
    override fun getPageTitle(position: Int): CharSequence? {
        return titles[position]
    }
}

class ServerFragment : Fragment() {
    private val server by lazy { BleServer(requireContext()) }
    private val executor = Executors.newSingleThreadExecutor()
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return LayoutInflater.from(activity)
            .inflate(R.layout.server_fragment, container, false)
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("ServerFragment", "resume")
        executor.execute {
            server.logCallback = {
                logField?.post {
                    logField?.append("$it\n")
                    serverScroll?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
            server.start()
        }
    }
    
    override fun onPause() {
        Log.d("ServerFragment", "pause")
        executor.execute {
            server.stop()
            server.logCallback = null
        }
        super.onPause()
    }
}


class ClientFragment : Fragment() {
    private val client by lazy { BleClient(requireContext()) }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return LayoutInflater.from(activity).inflate(R.layout.client_fragment, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hideProgress()
        button.setOnClickListener { sendMessage() }
    }
    
    private fun sendMessage() {
        showProgress()
        val message = messageInputField.editableText.toString()
        client.send(message) { result ->
            button.post {
                messageLog?.append("message \"$message\" ${if (result) "sent" else "not sent"}\n")
                clientScroll?.fullScroll(ScrollView.FOCUS_DOWN)
                hideProgress()
                messageInputField.text?.clear()
            }
        }
    }
    
    @SuppressLint("Visibility")
    private fun showProgress() {
        progress?.visibility = View.VISIBLE
        button?.visibility = View.INVISIBLE
    }
    
    @SuppressLint("Visibility")
    private fun hideProgress() {
        progress?.visibility = View.INVISIBLE
        button?.visibility = View.VISIBLE
    }
}
