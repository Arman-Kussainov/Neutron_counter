package com.hoho.android.usbserial.examples;

import static java.lang.Math.round;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private static final String METAG = "CCCP";

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;

    private ControlLines controlLines;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    // works. will be used later for a nicer screen fill
    int height = Resources.getSystem().getDisplayMetrics().heightPixels;
    int width = Resources.getSystem().getDisplayMetrics().widthPixels;

    // space allotted for the registered signal
    int window_height = (int)(1.1*(double)height/2.0);

    Bitmap mybitmap = Bitmap.createBitmap(width, window_height, Bitmap.Config.ARGB_8888);

    int frame_count = 0;
    int color_switch = 0;
    long time_start = 0, time_passed = 0;
    int events_counter=1;
    double count_speed = 0;

        int low_noise;
        int high_noise;

        // data range to display
        double min, max;
        double resolution;

        // how thick should be data point displayed
        int half_width_y;
        int half_width_x;

        int data_points;
        int frame_width;
        int max_frames;


    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        View receiveBtn = view.findViewById(R.id.receive_btn);
        controlLines = new ControlLines(view);


        //mybitmap.eraseColor(Color.BLACK);

        if (withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // should show progress bar instead of blocking UI thread
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch (UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {

        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /*
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
            controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        controlLines.stop();
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {
        }
        usbSerialPort = null;
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {

            byte[] data = (str + '\n').getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }


    private void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Check if this line responsible for the length of data piece
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }


    private void receive(byte[] data) {

        low_noise = 509;
        high_noise = 513;

        // data range to display
        min = 200; max = 800;
        resolution = (double) window_height / (max - min);

        // how thick should be data point displayed
        half_width_y = 3;
        half_width_x = 3;

        //int frame_width=31*2; //<---- WHY?... see full formula below
        // I'm assuming/detecting that we have 62 bytes-> 31 data points. Probably is determined by a buffer size
        // my buffer size is 62 bytes. It is devices specific (most probably) and ... whatever)
        data_points = data.length / 2;
        //int frame_width = (2 * half_width_x + 1) * data_points - (data_points - 1) * half_width_x * if_zero;
        frame_width = (2 * half_width_x + 1) * data_points - (data_points - 1) * half_width_x;
        max_frames = width/frame_width;


        SpannableStringBuilder spn = new SpannableStringBuilder();
        //spn.append("receive " + data.length + " bytes\n");
        if (data.length >= 0) {
            //packets_counter++;
            // comment out the next line if now numerical output is necessary
            // spn.append(HexDump.dumpHexString(data)).append("\n");
            int events=HexDump.returnCounts(data);

            if (events != 0) {

                if (frame_count == max_frames) {
                    spn.append(String.valueOf(round(count_speed*10d)/10d)).
                            append(" counts/second").append("\n");
                }

                // To plot data
                GraphicalOutput(data);

                events_counter+=events;
/*                // number of events per current data buffer
                    spn.append("[").
                        append(String.valueOf(events)).
                        append("] ").
                        append("\t");
*/

            }

        }

        // comment out the next line if now numerical output is necessary
        receiveText.append(spn);
    }

    private void GraphicalOutput(byte[] array) {

        ImageView localView = (ImageView) getView().findViewById(R.id.graph);

        // as soon as the previous max_frames have been displayed, we can do this
        if (frame_count == max_frames) {
            // at this point, we are stepping into the max_frame+1 events
            // so the UI will display the previous count speed
            time_passed=System.currentTimeMillis()-time_start;
            count_speed=Double.valueOf(events_counter)*1000.0d/Double.valueOf(time_passed);

            //receiveText.append("\n");
            //receiveText.append(String.valueOf(count_speed));
            //receiveText.append("\n");

            frame_count = 0;
            events_counter=0;
            mybitmap.eraseColor(Color.BLACK);

            color_switch = 0;
            // It clears the whole roll of the scrolling text
            receiveText.setText(null);
            time_start=System.currentTimeMillis();
        }


        final Runnable PLotData = new Runnable() {
            private final char[] HEX_DIGITS = {
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
            };

            @Override
            public void run() {

/*
                int low_noise = 509;
                int high_noise = 513;

                // data range to display
                //double min = 300, max = 600;
                double min = 0, max = 800;
                //int window_height = 1000;
                double resolution = (double) window_height / (max - min);

                // how thick should be data point displayed
                int half_width_y = 3;
                int half_width_x = 3;
*/

                // the leftovers from the previous algorithms
                // seems now it is working just fine
                //int if_zero = 0;
                //if (half_width_x >= 1) {
                //    if_zero = 1;
                //}

/*
                //int frame_width=31*2; //<---- WHY?... see full formula below
                // I'm assuming/detecting that we have 62 bytes-> 31 data points. Probably is determined by a buffer size
                // my buffer size is 62 bytes. It is devices specific (most probably) and ... whatever)
                int data_points = array.length / 2;
                //int frame_width = (2 * half_width_x + 1) * data_points - (data_points - 1) * half_width_x * if_zero;
                int frame_width = (2 * half_width_x + 1) * data_points - (data_points - 1) * half_width_x;
                int max_frames = width/frame_width;
*/

                for (int i = 0; i < array.length - 1; i += 2) {

                    StringBuilder signal = new StringBuilder();

                    // byte to HEX and then to decimal...could be shorter :)
                    byte b1 = array[i];
                    signal.append(HEX_DIGITS[(b1 >>> 4) & 0x0F]);
                    signal.append(HEX_DIGITS[b1 & 0x0F]);
                    byte b2 = array[i + 1];
                    signal.append(HEX_DIGITS[(b2 >>> 4) & 0x0F]);
                    signal.append(HEX_DIGITS[b2 & 0x0F]);
                    int signal_decimal = Integer.parseInt(String.valueOf(signal), 16);

                    //if (signal_decimal < low_noise || signal_decimal > high_noise) {// To cut the noise

                    int y = (int) (((double) signal_decimal - min) * resolution);
//                // I'm assuming that we have 62 bytes-> 31 data points. Probably it is determined by a buffer size

                    if (y >= 0 + half_width_y && y < window_height - half_width_y) {

                        // next two loops are designated to draw a fat data point
                        for (int j = -half_width_x; j <= half_width_x; j++) {
                            for (int k = -half_width_y; k <= half_width_y; k++) {

                                //if (signal_decimal < low_noise || signal_decimal > high_noise) {// To cut the noise from display
                                // color_switch was introduced to see the parts of the signal from different data bursts
                                if (color_switch % 2 == 0) {
                                    mybitmap.setPixel((i / 2 + half_width_x) + half_width_x * (i / 2) + j + frame_count * frame_width,
                                            window_height - 1 - y + k,
                                            Color.argb(255, 0, 255, 0));
                                } else {
                                    mybitmap.setPixel((i / 2 + half_width_x) + half_width_x * (i / 2) + j + frame_count * frame_width,
                                            window_height - 1 - y + k,
                                            Color.argb(255, 255, 0, 0));
                                }
                                //}
                                //else{
                                // plot the noise. clean later the code
                                //    mybitmap.setPixel((i / 2 + half_width_x) + half_width_x * (i / 2) + j + frame_count * frame_width,
                                //            window_height - 1 - y + k,
                                //            Color.argb(255, 0, 0, 255));
                                //}

                            }
                        }
                    }

                    //}

                    //((ImageView) localView).setImageBitmap(mybitmap);

                }

                frame_count++;
                color_switch++;

                ((ImageView) localView).setImageBitmap(mybitmap);
                // seems does not make a bigger difference
                //((ImageView) localView).invalidate();

                //receiveText.append(String.valueOf(neutron_count)+" ");
                //receiveText.append(String.valueOf(frame_count)+" ");

//                if (color_switch % (10 * max_frames) == 0) {
//                    mybitmap.eraseColor(Color.BLACK);
//                    color_switch = 0;
//                }


            }

        };
        PLotData.run();

    }


    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (!connected) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) {
                    ctrl = "RTS";
                    usbSerialPort.setRTS(btn.isChecked());
                }
                if (btn.equals(dtrBtn)) {
                    ctrl = "DTR";
                    usbSerialPort.setDTR(btn.isChecked());
                }
            } catch (IOException e) {
                status("set" + ctrl + "() failed: " + e.getMessage());
            }
        }

        private void run() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);


            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS))
                    rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS))
                    ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR))
                    dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR))
                    dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))
                    cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))
                    riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }
}
