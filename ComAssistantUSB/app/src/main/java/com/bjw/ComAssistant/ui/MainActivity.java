package com.bjw.ComAssistant.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Base64;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.bjw.ComAssistant.R;
import com.bjw.ComAssistant.SerialHelper;
import com.bjw.ComAssistant.ShellUtils;
import com.bjw.bean.AssistBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    SerialControl serialControl;
    DispQueueThread DispQueue;

    EditText etDisplayData, etInputData, etTimeSendData, etManualPortName;
    Boolean isRun = true;
    AssistBean AssistData;
    Boolean isConnected = false;

    String sFilename = "ComAssistant";
    String sLinename = "AssistData";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etDisplayData = findViewById(R.id.editTextRecDisp1);
        etInputData = findViewById(R.id.editTextCOMA);
        etTimeSendData = findViewById(R.id.editTextTimeCOMA);
        etManualPortName = findViewById(R.id.editTextManualPort);

        Button btnClear = findViewById(R.id.btnClear);
        btnClear.setOnClickListener(view -> {
            etDisplayData.setText("");
            etInputData.setText("");
        });

        Button btnSendData = findViewById(R.id.btnSendData);
        btnSendData.setOnClickListener(view -> {
            CharSequence t = etInputData.getText();
            char[] text = new char[t.length()];
            for (int i = 0; i < t.length(); i++) {
                text[i] = t.charAt(i);
            }
            sendPortData(serialControl, text);
        });

        Spinner spinnerBaudRateCOMA = findViewById(R.id.SpinnerBaudRateCOMA);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.baudrates_name,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBaudRateCOMA.setAdapter(adapter);
        spinnerBaudRateCOMA.setSelection(16);

        Spinner spinnerPortList = findViewById(R.id.spinnerPortList);
        ArrayAdapter<String> adapterPort = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, getListPort());
        adapterPort.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPortList.setAdapter(adapterPort);
        spinnerPortList.setSelection(0);

        ToggleButton toggleButtonCOMA = findViewById(R.id.toggleButtonCOMA);
        toggleButtonCOMA.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                CheckComA(spinnerPortList, spinnerBaudRateCOMA, toggleButtonCOMA);
                isConnected = true;
            } else {
                isConnected = false;
                closeComPort(serialControl);
            }
        });
    }

    @Override
    public void onPause() {
        saveAssistData(AssistData);
        super.onPause();
    }

    @Override
    protected void onResume() {

        super.onResume();
        if (DispQueue != null)
            return;

        isRun = true;
        DispQueue = new DispQueueThread();
        DispQueue.setName("DispQueue");
        DispQueue.start();

        AssistData = getAssistData();
        displayAssistData(AssistData);
    }


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        closeComPort(serialControl);
    }

    /**
     * Adtrishan : /dev/ttyUSB0
     * Magnetic North : /dev/ttyS5
     */

    private void CheckComA(Spinner spinnerPort, Spinner spinnerBaudRate, ToggleButton toggleButtonCOMA) {
        String manualInput = etManualPortName.getText().toString();
        String portName;
        if (manualInput.isEmpty()) {
            String selectedPort = spinnerPort.getSelectedItem().toString();
            portName = "/dev/" + selectedPort;
        } else {
            portName = "/dev/" + manualInput;
        }

        serialControl = new SerialControl(portName);
        serialControl.setBaudRate(getResources().getIntArray(R.array.baudrates_value)[spinnerBaudRate.getSelectedItemPosition()]);
        serialControl.setParity(0);
        openComPort(serialControl, toggleButtonCOMA);
    }

    private class SerialControl extends SerialHelper {

        public SerialControl(String sPort) {
            super(sPort);
        }

        @Override
        protected void onDataReceived(final byte[] ComRecData) {
            DispQueue.AddQueue(ComRecData);
            DispQueue.setResume();

        }
    }

    private class DispQueueThread extends Thread {
        private BlockingQueue<byte[]> QueueList = new LinkedBlockingQueue<byte[]>();

        @Override
        public void run() {
            super.run();
            while (!isInterrupted()) {
                if (!isRun)
                    break;
                while ((!QueueList.isEmpty())) {
                    if (!isRun)
                        break;
                    final byte[] ComData = QueueList.poll();
                    runOnUiThread(() -> {
                        assert ComData != null;
                        displayRecData(ComData);
                    });
                    try {
                        Thread.sleep(5);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public synchronized void AddQueue(byte[] ComData) {
            QueueList.add(ComData);
        }

        public synchronized void setResume() {
            notify();
        }
    }

    private void displayRecData(byte[] comRecData) {
        int nComPort = comRecData[0];
        int size = comRecData.length - 1;
        byte[] ComRecData = new byte[size];
        System.arraycopy(comRecData, 1, ComRecData, 0, size);

        StringBuilder sMsg = new StringBuilder();
        sMsg.append(new String(ComRecData));

        if (nComPort == serialControl.getnPort() && isConnected) {
            etDisplayData.append(sMsg);
        }
    }

    private void displayAssistData(AssistBean AssistData) {
        etInputData.setText(AssistData.getSendA());
        etTimeSendData.setText(AssistData.sTimeA);
    }

    private void saveAssistData(AssistBean AssistData) {
        AssistData.sTimeA = etTimeSendData.getText().toString();
        AssistData.isTxt = true;
        AssistData.SendTxtA = etInputData.getText().toString();

        SharedPreferences msharedPreferences = getSharedPreferences(
                sFilename, Context.MODE_PRIVATE);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(AssistData);
            String sBase64 = new String(Base64.encode(baos.toByteArray(), 0));
            SharedPreferences.Editor editor = msharedPreferences.edit();
            editor.putString(sLinename, sBase64);
            editor.commit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private AssistBean getAssistData() {
        SharedPreferences msharedPreferences = getSharedPreferences(
                sFilename, Context.MODE_PRIVATE);
        AssistBean AssistData = new AssistBean();
        try {
            String personBase64 = msharedPreferences
                    .getString(sLinename, "");
            byte[] base64Bytes = Base64.decode(personBase64.getBytes(), 0);
            ByteArrayInputStream bais = new ByteArrayInputStream(base64Bytes);
            ObjectInputStream ois = new ObjectInputStream(bais);
            AssistData = (AssistBean) ois.readObject();
            return AssistData;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return AssistData;
    }

    private void sendPortData(SerialHelper ComPort, char[] sOut) {
        if (ComPort != null && ComPort.isOpen()) {
            ComPort.sendData(sOut, true);
        }
    }

    private void closeComPort(SerialHelper ComPort) {
        if (ComPort != null) {
            ComPort.destroySend();
            ComPort.close();
        }
    }

    private void openComPort(SerialHelper comPort, ToggleButton toggleComConnect) {
        try {
            comPort.open();
        } catch (SecurityException e) {
            if (comPort.getnPort() == 0)
                toggleComConnect.setChecked(false);
            ShowMessage(getString(R.string.nopermission));
        } catch (IOException e) {
            if (comPort.getnPort() == 0)
                toggleComConnect.setChecked(false);
            ShowMessage(getString(R.string.unknownerr));
        } catch (InvalidParameterException e) {
            if (comPort.getnPort() == 0)
                toggleComConnect.setChecked(false);
            ShowMessage(getString(R.string.parametererr));
        }
    }

    @Override
    protected void onDestroy() {
        if (DispQueue != null) {
            isRun = false;
            DispQueue.setResume();
        }
        closeComPort(serialControl);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
        super.onDestroy();

    }

    private void ShowMessage(String sMsg) {
        Toast.makeText(this, sMsg, Toast.LENGTH_SHORT).show();
    }

    protected List<String> getListPort() {
        ShellUtils.CommandResult rst = ShellUtils.execCommand("ls -l /sys/class/tty/tty*", false);
        Pattern pattern = Pattern.compile("/sys/class/tty/(\\w+) -> .*?/tty/(\\w+)");
        List<String> portList = new ArrayList<>();

        if (rst.result == 0) {
            String info = rst.successMsg;
            if (info == null) {
                return Collections.singletonList("unknown");
            }

            Matcher matcher = pattern.matcher(info);

            while (matcher.find()) {
                String data = matcher.group(1);
                portList.add(data);
            }
        }

        return portList.isEmpty() ? Collections.singletonList("unknown") : portList;
    }
}