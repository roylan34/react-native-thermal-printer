package com.reactnativethermalprinter;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.Manifest;
import android.os.Build;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.dantsu.escposprinter.EscPosPrinter;
import com.dantsu.escposprinter.connection.DeviceConnection;
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections;
import com.dantsu.escposprinter.connection.tcp.TcpConnection;
import com.dantsu.escposprinter.exceptions.EscPosBarcodeException;
import com.dantsu.escposprinter.exceptions.EscPosConnectionException;
import com.dantsu.escposprinter.exceptions.EscPosEncodingException;
import com.dantsu.escposprinter.exceptions.EscPosParserException;
import com.dantsu.escposprinter.textparser.PrinterTextParserImg;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.PermissionListener;
import com.facebook.react.modules.core.PermissionAwareActivity;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.text.TextUtils;

import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection;

import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

@ReactModule(name = ThermalPrinterModule.NAME)
public class ThermalPrinterModule extends ReactContextBaseJavaModule implements PermissionListener {

  public static final String NAME = "ThermalPrinterModule";

  private static ReactApplicationContext reactContext;
  
  private static final String[] BLUETOOTH_PERMISSIONS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
    new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} :
    new String[] { Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN };

  private ArrayList<BluetoothConnection> btDevicesList = new ArrayList();

  private static final int BLUETOOTH_DEVICE_LIST_PERMISSION_REQUEST_CODE = 1;
  private static final int BLUETOOTH_PRINT_PERMISSION_REQUEST_CODE = 2;

  private Promise jsPromise;
  private String macAddress; 
  private String payload;
  private boolean autoCut;
  private boolean openCashbox;
  private double mmFeedPaper;
  private double printerDpi;
  private double printerWidthMM;
  private double printerNbrCharactersPerLine;

  public ThermalPrinterModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  @NonNull
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void printTcp(String ipAddress, double port, String payload, boolean autoCut, boolean openCashbox, double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, double timeout, Promise promise) {
//
//        05-05-2021
//        https://reactnative.dev/docs/native-modules-android
//        The following types are currently supported but will not be supported in TurboModules. Please avoid using them:
//
//        Integer -> ?number
//        int -> number
//        Float -> ?number
//        float -> number
//
    this.jsPromise = promise;
    this.payload = payload;
    this.autoCut = autoCut;
    this.openCashbox = openCashbox;
    this.mmFeedPaper = mmFeedPaper;
    this.printerDpi = printerDpi;
    this.printerWidthMM = printerWidthMM;
    this.printerNbrCharactersPerLine = printerNbrCharactersPerLine;
    try {
      TcpConnection connection = new TcpConnection(ipAddress, (int) port, (int) timeout);
      this.printIt(connection);
    } catch (Exception e) {
      this.jsPromise.reject("Connection Error", e.getMessage());
    }
  }

  @ReactMethod
  public void printBluetooth(String macAddress, String payload, boolean autoCut, boolean openCashbox, double mmFeedPaper, double printerDpi, double printerWidthMM, double printerNbrCharactersPerLine, Promise promise) {
    this.jsPromise = promise;
    this.macAddress = macAddress;
    this.payload = payload;
    this.autoCut = autoCut;
    this.openCashbox = openCashbox;
    this.mmFeedPaper = mmFeedPaper;
    this.printerDpi = printerDpi;
    this.printerWidthMM = printerWidthMM;
    this.printerNbrCharactersPerLine = printerNbrCharactersPerLine;
    boolean hasPermissions = true;
    PermissionAwareActivity activity = (PermissionAwareActivity) getCurrentActivity();
    PermissionListener listner = this;
    List<String> requiredPermissions = new ArrayList<>();
    for (String permission : BLUETOOTH_PERMISSIONS) {
      if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
        requiredPermissions.add(permission);
        hasPermissions = false;
      }
    }
    if (!requiredPermissions.isEmpty()) {
      activity.requestPermissions(requiredPermissions.toArray(new String[requiredPermissions.size()]), BLUETOOTH_PRINT_PERMISSION_REQUEST_CODE, listner);
    }
    if (hasPermissions) {
      doPrintBluetooth();
    }
  }

  private void doPrintBluetooth() {
    BluetoothConnection btPrinter;
    if (TextUtils.isEmpty(macAddress)) {
      btPrinter = BluetoothPrintersConnections.selectFirstPaired();
    } else {
      btPrinter = getBluetoothConnectionWithMacAddress(macAddress);
    }
    if (btPrinter == null) {
      AlertDialog.Builder builder = new AlertDialog.Builder(getCurrentActivity());
      builder.setMessage("No bluetooth printer found. Please enable bluetooth and pair your printer first.");
      builder.setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          Intent intent = new Intent();
          intent.setAction(Settings.ACTION_BLUETOOTH_SETTINGS);
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          reactContext.startActivity(intent);
          dialog.dismiss();
        }
      });
      builder.setNegativeButton("No thanks", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          dialog.dismiss();
        }
      });
      AlertDialog dialog = builder.create();
      dialog.show();
      this.jsPromise.reject("Connection Error", "Bluetooth printer not found");
    }  
    try {
      this.printIt(btPrinter.connect());
    } catch (Exception e) {
      this.jsPromise.reject("Connection Error", e.getMessage());
    }
  }

  @ReactMethod
  public void getBluetoothDeviceList(Promise promise) {
    this.jsPromise = promise;
    boolean hasPermissions = true;
    PermissionAwareActivity activity = (PermissionAwareActivity) getCurrentActivity();
    PermissionListener listner = this;
    List<String> requiredPermissions = new ArrayList<>();
    for (String permission : BLUETOOTH_PERMISSIONS) {
      if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
        requiredPermissions.add(permission);
        hasPermissions = false;
      }
    }
    if (!requiredPermissions.isEmpty()) {
      activity.requestPermissions(requiredPermissions.toArray(new String[requiredPermissions.size()]), BLUETOOTH_DEVICE_LIST_PERMISSION_REQUEST_CODE, listner);
    }
    if (hasPermissions) {
      doGetBluetoothDeviceList();
    }
  }

  private void doGetBluetoothDeviceList() {
    try {
      Set<BluetoothDevice> pairedDevices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
      WritableArray rnArray = new WritableNativeArray();
      if (pairedDevices.size() > 0) {
        for (BluetoothDevice device : pairedDevices) {
          this.btDevicesList.add(new BluetoothConnection(device));
          JSONObject jsonObj = new JSONObject();

          String deviceName = device.getName();
          String macAddress = device.getAddress();

          jsonObj.put("deviceName", deviceName);
          jsonObj.put("macAddress", macAddress);
          WritableMap wmap = convertJsonToMap(jsonObj);
          rnArray.pushMap(wmap);
        }
      }
      this.jsPromise.resolve(rnArray);
      } catch (Exception e) {
        this.jsPromise.reject("Bluetooth Error", e.getMessage());
      }
  }

  private Bitmap getBitmapFromUrl(String url) {
    try {
      Bitmap bitmap = Glide
        .with(getCurrentActivity())
        .asBitmap()
        .load(url)
        .submit()
        .get();
      return bitmap;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Synchronous printing
   */

  private String preprocessImgTag(EscPosPrinter printer, String text) {
    Pattern p = Pattern.compile("(?<=\\<img\\>)(.*)(?=\\<\\/img\\>)");
    Matcher m = p.matcher(text);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String firstGroup = m.group(1);
      m.appendReplacement(sb, PrinterTextParserImg.bitmapToHexadecimalString(printer, getBitmapFromUrl(firstGroup)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private void printIt(DeviceConnection printerConnection) {
    try {
      EscPosPrinter printer = new EscPosPrinter(printerConnection, (int) printerDpi, (float) printerWidthMM, (int) printerNbrCharactersPerLine);
      String processedPayload = preprocessImgTag(printer, payload);
      if (openCashbox) {
        printer.printFormattedTextAndOpenCashBox(processedPayload, (float) mmFeedPaper);
      } else if (autoCut) {
        printer.printFormattedTextAndCut(processedPayload, (float) mmFeedPaper);
      } else {
        printer.printFormattedText(processedPayload, (float) mmFeedPaper);
      }
      printer.disconnectPrinter();
      this.jsPromise.resolve(true);
    } catch (EscPosConnectionException e) {
      this.jsPromise.reject("Broken connection", e.getMessage());
    } catch (EscPosParserException e) {
      this.jsPromise.reject("Invalid formatted text", e.getMessage());
    } catch (EscPosEncodingException e) {
      this.jsPromise.reject("Bad selected encoding", e.getMessage());
    } catch (EscPosBarcodeException e) {
      this.jsPromise.reject("Invalid barcode", e.getMessage());
    } catch (Exception e) {
      this.jsPromise.reject("ERROR", e.getMessage());
    }
  }

  private BluetoothConnection getBluetoothConnectionWithMacAddress(String macAddress) {
    for (BluetoothConnection device : this.btDevicesList) {
      if (device.getDevice().getAddress().contentEquals(macAddress))
        return device;
    }
    return null;
  }

  private static WritableMap convertJsonToMap(JSONObject jsonObject) throws JSONException {
    WritableMap map = new WritableNativeMap();
    Iterator<String> iterator = jsonObject.keys();
    while (iterator.hasNext()) {
      String key = iterator.next();
      Object value = jsonObject.get(key);
      if (value instanceof JSONObject) {
        map.putMap(key, convertJsonToMap((JSONObject) value));
      } else if (value instanceof Boolean) {
        map.putBoolean(key, (Boolean) value);
      } else if (value instanceof Integer) {
        map.putInt(key, (Integer) value);
      } else if (value instanceof Double) {
        map.putDouble(key, (Double) value);
      } else if (value instanceof String) {
        map.putString(key, (String) value);
      } else {
        map.putString(key, value.toString());
      }
    }
    return map;
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    boolean hasPermissions = true;
    if (grantResults.length > 0) {
      for (int i = 0; i < grantResults.length; i++) {
        if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
          hasPermissions = false;
          PermissionAwareActivity activity = (PermissionAwareActivity) getCurrentActivity();
          boolean showRationale = activity.shouldShowRequestPermissionRationale(permissions[i]);
          if (showRationale) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getCurrentActivity());
            builder.setMessage("Bluetooth permisssion is required for accessing printer. Please allow the permission.");
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
              }
            });
            AlertDialog dialog = builder.create();
            dialog.show();
            this.jsPromise.reject("Permission Error", "Required permissions were not granted");
          } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(getCurrentActivity());
            builder.setMessage("Bluetooth permisssion was denied more than once. You may allow the permission from settings.");
            builder.setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Uri uri = Uri.fromParts("package", getCurrentActivity().getPackageName(), null);
                intent.setData(uri);
                reactContext.startActivity(intent);
                dialog.dismiss();
              }
            });
            builder.setNegativeButton("No thanks", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
              }
            });
            AlertDialog dialog = builder.create();
            dialog.show();
            this.jsPromise.reject("Permission Error", "Required permissions are permanently denied");
          }
          break;
        } 
      } 
    } else {
      hasPermissions = false;
      this.jsPromise.reject("Permission Error", "Required permissions were not granted");
    }
    if (hasPermissions) {
      switch (requestCode) {
        case BLUETOOTH_DEVICE_LIST_PERMISSION_REQUEST_CODE:
          doGetBluetoothDeviceList();
          break;
        case BLUETOOTH_PRINT_PERMISSION_REQUEST_CODE:
          doPrintBluetooth();
          break;
        default:
          this.jsPromise.reject("Request Code Error", "Invalid request code");
      }
    }
    return true;
  }

}
