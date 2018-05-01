package uk.co.froot.trezorjava.core;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import uk.co.froot.trezorjava.core.internal.TrezorDevice;

import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbException;
import javax.usb.UsbHostManager;
import javax.usb.UsbServices;
import javax.usb.event.UsbServicesEvent;
import javax.usb.event.UsbServicesListener;

import static uk.co.froot.trezorjava.core.TrezorType.*;

/**
 * Manager class to provide the following:
 *
 * Access to the top level Trezor API.
 */
public class TrezorManager {

  private static final Logger log = LoggerFactory.getLogger(TrezorManager.class);

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
   * The callback handler.
   */
  private final TrezorEventHandler trezorEventHandler;

  /**
   * Creates the manager wrapper for the device and initialises appropriate USB libraries.
   * Ensure you close the manager to release resources.
   *
   * @param trezorEventHandler The TrezorEvent handler.
   *
   * @throws LibUsbException If something goes wrong.
   */
  public TrezorManager(DefaultTrezorEventHandler trezorEventHandler) throws LibUsbException, UsbException {

    this.trezorEventHandler = trezorEventHandler;
    this.usbServices = UsbHostManager.getUsbServices();

    initLibUsb();

    initUsbListener(trezorEventHandler);

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
   * @throws InvalidProtocolBufferException If something goes wrong.
   */
  public Message sendMessage(Message message) throws InvalidProtocolBufferException {

    // Fail fast
    if (trezorDevice == null) {
      log.warn("Device is not present");
      return null;
    }

    return trezorDevice.sendMessage(message);
  }

  /**
   * Clean up libusb resources.
   */
  public void close() {
    LibUsb.exit(null);
  }

  /**
   * Initialise the USB listener.
   *
   * @param trezorEventHandler The Trezor event handler.
   *
   * @throws UsbException If something goes wrong.
   */
  private void initUsbListener(DefaultTrezorEventHandler trezorEventHandler) throws UsbException {
    usbServices.addUsbServicesListener(new UsbServicesListener() {

      public void usbDeviceAttached(UsbServicesEvent usbServicesEvent) {

        // Obtain the descriptor
        UsbDeviceDescriptor descriptor = usbServicesEvent.getUsbDevice().getUsbDeviceDescriptor();

        // Attempt to identify the device
        TrezorType trezorType = identifyTrezorDevice(descriptor);

        if (trezorType != UNKNOWN) {
          log.debug("Device attached: {}", trezorType);

          // Attempt to open the device
          if (tryOpenDevice(trezorType, descriptor.idVendor(), descriptor.idProduct())) {
            // Issue a callback
            trezorEventHandler.handleDeviceAttached(trezorType, descriptor);
          }
        }
      }

      public void usbDeviceDetached(UsbServicesEvent usbServicesEvent) {

        // Obtain the descriptor
        UsbDeviceDescriptor descriptor = usbServicesEvent.getUsbDevice().getUsbDeviceDescriptor();

        // Attempt to identify the device
        TrezorType trezorType = identifyTrezorDevice(descriptor);

        if (trezorType != UNKNOWN) {
          log.debug("Device detached: {}", trezorType);

          // Remove the device from management
          if (trezorDevice != null) {
            trezorDevice.close();
            trezorDevice = null;
          }

          // Issue a callback
          trezorEventHandler.handleDeviceDetached(trezorType, descriptor);
        }
      }
    });
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

}
