package uk.co.froot.trezorjava.core;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import uk.co.froot.trezorjava.core.events.TrezorEvent;
import uk.co.froot.trezorjava.core.events.TrezorEvents;
import uk.co.froot.trezorjava.core.exceptions.TrezorException;
import uk.co.froot.trezorjava.core.internal.TrezorDevice;

import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbException;
import javax.usb.UsbHostManager;
import javax.usb.UsbServices;
import javax.usb.event.UsbServicesEvent;
import javax.usb.event.UsbServicesListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static uk.co.froot.trezorjava.core.TrezorDeviceState.*;
import static uk.co.froot.trezorjava.core.TrezorType.*;

/**
 * <p>Manager class to provide a low level interface to the device. It offers message passing,
 * event reception facilities and a device context.</p>
 *
 * @since 0.0.1
 */
public class TrezorDeviceManager implements UsbServicesListener {

  private static final Logger log = LoggerFactory.getLogger(TrezorDeviceManager.class);

  private static final int TREZOR_INTERFACE = 0;

  /**
   * The Trezor device.
   */
  private TrezorDevice trezorDevice = null;

  /**
   * The USB services.
   */
  private final UsbServices usbServices;

  /**
   * The Trezor device context.
   */
  private final TrezorContext deviceContext = new TrezorContext();

  /**
   * The executor to handle USb polling for reading data.
   */
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

  /**
   * Creates the manager wrapper for the device and initialises appropriate USB libraries.
   * Ensure you close the manager to release resources.
   *
   * @throws LibUsbException If something goes wrong.
   */
  public TrezorDeviceManager() throws TrezorException {

    try {
      this.usbServices = UsbHostManager.getUsbServices();
      initLibUsb();
      initUsbListener();
    } catch (UsbException e) {
      throw new TrezorException("Unable to get javax USB services.", e);
    } catch (LibUsbException e) {
      throw new TrezorException("Unable to get libusb services.", e);
    }

  }

  /**
   * Await the connection of a valid Trezor device.
   *
   * This method blocks until the device context is updated to reflect the new state,
   * usually by a USB attach/detach event.
   */
  public void awaitDevice() {
    // Wait for a device to be present (blocking)
    while (deviceContext.getDeviceState() != DEVICE_ATTACHED) {
      try {
        // Strike a balance between CPU loading and user responsiveness
        Thread.sleep(400);
      } catch (InterruptedException e) {
        throw new TrezorException("Unexpected interruption", e);
      }
    }

  }

  /**
   * Send a message to the device.
   *
   * This is a blocking call.
   *
   * @param message A protobuf message to send to the device.
   *
   * @return A response message from the device.
   *
   * @throws TrezorException If something goes wrong.
   */
  public void sendMessage(Message message) throws TrezorException {

    // Fail fast
    if (trezorDevice == null) {
      log.warn("Device is not present");
      return;
    }

    try {
      trezorDevice.sendMessage(message);

      // Device is connected if a message gets through
      deviceContext.setDeviceState(DEVICE_CONNECTED);

    } catch (InvalidProtocolBufferException e) {
      // Device has failed if a message fails
      deviceContext.setDeviceState(DEVICE_FAILED);
      throw new TrezorException("Message sending failed.", e);
    }
  }

  /**
   * @return The device context (connectivity, device type etc).
   */
  public TrezorContext context() {
    return deviceContext;
  }

  /**
   * Clean up libusb resources.
   */
  public void close() {
    log.info("Closing...");
    executor.shutdownNow();
    try {
      executor.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // Do nothing
    }
    LibUsb.exit(null);
    log.info("Closed");
  }

  /**
   * Initialise the USB listener.
   */
  private void initUsbListener() {

    // Add a low level USB listener for attachment messages
    usbServices.addUsbServicesListener(this);

  }

  /**
   * Initialise libusb context.
   */
  private void initLibUsb() {

    log.debug("Initialising libusb...");

    int result = LibUsb.init(null);
    if (result != LibUsb.SUCCESS) {
      throw new LibUsbException("Unable to initialize libusb.", result);
    }

  }

  /**
   * @param descriptor The USB device descriptor.
   *
   * @return True if this is a recognised Trezor device.
   */
  private TrezorType identifyTrezorDevice(UsbDeviceDescriptor descriptor) {

    if (descriptor.idVendor() == 0x534c && descriptor.idProduct() == 0x0001) {
      return V1;
    }

    // TREZOR V2 (Model T) - factory issue
    if (descriptor.idVendor() == 0x1209 && descriptor.idProduct() == 0x53c0) {
      return V2_FACTORY;
    }

    // TREZOR V2 (Model T) - firmware installed
    if (descriptor.idVendor() == 0x1209 && descriptor.idProduct() == 0x53c1) {
      return V2;
    }

    // Must have failed to be here
    return UNKNOWN;
  }

  /**
   * Try to get a Trezor device.
   *
   * @param trezorType The Trezor type inferred from the vid and pid (e.g. "V2").
   * @param vid        The vendor ID.
   * @param pid        The product ID.
   */
  private boolean tryOpenDevice(TrezorType trezorType, short vid, short pid) {

    // Fail fast
    if (trezorType == UNKNOWN) {
      return false;
    }

    try {
      openUsbDevice(vid, pid);
    } catch (LibUsbException e) {
      // No need for a stack trace here
      log.warn("Unable to open device", e.getMessage());
      return false;
    }

    return true;
  }

  private void openUsbDevice(short vid, short pid) {
    // Attempt to open the device
    DeviceHandle handle = LibUsb.openDeviceWithVidPid(null, vid, pid);
    if (handle == null) {
      throw new LibUsbException("Device not found ", LibUsb.ERROR_NOT_FOUND);
    }

    // Claim interface
    int result = LibUsb.claimInterface(handle, TREZOR_INTERFACE);
    if (result != LibUsb.SUCCESS) {
      throw new LibUsbException("Unable to claim interface.", result);
    }

    // Must have a Trezor device to be here
    trezorDevice = new TrezorDevice(handle);
  }

  @Override
  public void usbDeviceAttached(UsbServicesEvent usbServicesEvent) {

    // Obtain the descriptor
    UsbDeviceDescriptor descriptor = usbServicesEvent.getUsbDevice().getUsbDeviceDescriptor();

    // Attempt to identify the device
    TrezorType trezorType = identifyTrezorDevice(descriptor);

    if (trezorType != UNKNOWN) {

      // Attempt to open the device
      if (tryOpenDevice(trezorType, descriptor.idVendor(), descriptor.idProduct())) {
        // Update context
        deviceContext.setDeviceState(DEVICE_ATTACHED);
        deviceContext.setTrezorType(trezorType);

        final TrezorDeviceManager self = this;

        // Notify listeners
        TrezorEvents.notify(new TrezorEvent(self, null));

        log.debug("Device attached: {}", trezorType);

        // Start the read polling mechanism
        executor.scheduleAtFixedRate(new Runnable() {
          @Override
          public void run() {
            try {
              // LibUsb will wait for 400ms for a message
              Message message = trezorDevice.receiveMessage();

              if (message != null) {
                // Notify others of the event
                TrezorEvents.notify(new TrezorEvent(self, message));
              }


            } catch (InvalidProtocolBufferException e) {
              e.printStackTrace();
            }


          }
        },
          500, // Wait for device to wake up before attempting to read
          500, // Allow for a 400ms timeout when reading
          TimeUnit.MILLISECONDS
        );


      }
    }
  }

  @Override
  public void usbDeviceDetached(UsbServicesEvent usbServicesEvent) {

    // Obtain the descriptor
    UsbDeviceDescriptor descriptor = usbServicesEvent.getUsbDevice().getUsbDeviceDescriptor();

    // Attempt to identify the device
    TrezorType trezorType = identifyTrezorDevice(descriptor);

    if (trezorType != UNKNOWN) {
      log.debug("Device detached: {}", trezorType);

      // Update context
      deviceContext.setDeviceState(DEVICE_DETACHED);
      deviceContext.setTrezorType(trezorType);

      // Remove the device from management
      if (trezorDevice != null) {
        trezorDevice.close();
        trezorDevice = null;
      }

      // Notify listeners
      TrezorEvents.notify(new TrezorEvent(this, null));
    }
  }
}
