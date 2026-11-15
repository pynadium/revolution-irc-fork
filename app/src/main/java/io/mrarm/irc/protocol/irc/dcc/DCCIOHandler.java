package io.mrarm.irc.protocol.irc.dcc;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DCCIOHandler implements Runnable {

    // java.util.logging keeps protocol/ free of Android imports; on Android it ends up in logcat
    private static final Logger LOG = Logger.getLogger("DCCIOHandler");

    private static final DCCIOHandler instanceSingleton = new DCCIOHandler();

    public static DCCIOHandler getInstance() {
        return instanceSingleton;
    }


    private Thread thread;
    private Selector selector;
    private final Object selectorLock = new Object();
    private boolean running = false;

    public DCCIOHandler() {
        try {
            selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SelectionKey register(AbstractSelectableChannel channel, int ops, SelectHandler callback)
            throws ClosedChannelException {
        SelectionKey ret;
        synchronized (selectorLock) {
            selector.wakeup();
            ret = channel.register(selector, ops, callback);
        }
        startIfNeeded();
        return ret;
    }

    public void unregister(SelectionKey key) {
        synchronized (selectorLock) {
            selector.wakeup();
            key.cancel();
        }
    }

    @Override
    public void run() {
        while (true) {
            synchronized (selectorLock) {
                if (selector.keys().size() <= 0) {
                    running = false;
                    break;
                }
            }
            try {
                selector.select();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "select() failed", e);
            }
            // Ready keys must be removed once handled: the selector never clears this set itself,
            // so leaving them makes every handler run again on every wakeup
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next();
                it.remove();
                try {
                    ((SelectHandler) k.attachment()).onSelect(k);
                } catch (CancelledKeyException ignored) {
                } catch (IOException err) {
                    LOG.log(Level.WARNING, "DCC handler I/O error", err);
                } catch (RuntimeException err) {
                    // One faulty transfer must not kill this thread (and the process with it).
                    // Drop its channel so a handler that keeps throwing cannot spin the loop.
                    LOG.log(Level.SEVERE, "DCC handler threw, closing its channel", err);
                    k.cancel();
                    try {
                        k.channel().close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }



    void startIfNeeded() {
        synchronized (selectorLock) {
            if (running)
                return;
            running = true;
        }
        thread = new Thread(this);
        thread.start();
    }


    public interface SelectHandler {

        void onSelect(SelectionKey key) throws IOException;

    }

}
