package android_serialport_api;   // name must match the native library (open-source android-serialport-api)

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialPort {
    private FileDescriptor mFd;
    private FileInputStream in;
    private FileOutputStream out;

    public SerialPort(File device, int baudrate, int flags) throws IOException {
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) throw new IOException("cannot open " + device + " (permission or wrong path)");
        in = new FileInputStream(mFd);
        out = new FileOutputStream(mFd);
    }
    public InputStream getInputStream() { return in; }
    public OutputStream getOutputStream() { return out; }

    private static native FileDescriptor open(String path, int baudrate, int flags);
    public native void close();

    static { System.loadLibrary("serial_port"); }
}
