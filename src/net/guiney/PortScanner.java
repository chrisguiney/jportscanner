package net.guiney;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final public class PortScanner {

    private static final class ScanResult {

        private static final short RESULT_OPEN = 0;
        private static final short RESULT_CLOSED = 1;
        private static final short RESULT_TIMEOUT = 2;

        private final short result;
        private final int port;

        public ScanResult(int port, short result) {
            this.port = port;
            this.result = result;
        }

        public int getPort() {
            return port;
        }

        public boolean isOpen() {
            return this.result == RESULT_OPEN;
        }

        public static ScanResult newOpenResult(int port) {
            return new ScanResult(port, ScanResult.RESULT_OPEN);
        }

        public static ScanResult newClosedResult(int port) {
            return new ScanResult(port, ScanResult.RESULT_CLOSED);
        }

        public static ScanResult newTimeoutResult(int port) {
            return new ScanResult(port, ScanResult.RESULT_TIMEOUT);
        }

    }

    private final class Connector implements Callable<ScanResult> {
        private final String hostname;
        private final int port;
        public Connector(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
        }
        @Override
        public ScanResult call() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(hostname, port), 1000);
                return ScanResult.newOpenResult(port);
            } catch (SocketTimeoutException e) {
                return ScanResult.newTimeoutResult(port);
            } catch (ConnectException e) {
                return ScanResult.newClosedResult(port);
            } catch (IOException e) {
                e.printStackTrace();
                return ScanResult.newClosedResult(port);
            } finally {
                try {
                    s.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private final AtomicBoolean submitCompleted = new AtomicBoolean();
    private final BlockingQueue<Future<ScanResult>> results;
    private final String hostname;
    private final int portRangeLow;
    private final int portRangeHigh;

    public PortScanner(String hostname) {
        this.hostname = hostname;
        this.portRangeLow = 1;
        this.portRangeHigh = 64*1024;
        this.results = new ArrayBlockingQueue<Future<ScanResult>>(this.portRangeHigh - this.portRangeLow);
    }

    public void scanPorts() {
        startScanning();
    }

    public boolean completed() {
        return submitCompleted.get() && results.isEmpty();
    }

    public ScanResult getNextResult() {
        try {
            return this.results.take().get();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void startScanning() {
        final ExecutorService pool = Executors.newFixedThreadPool(500, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }
        });

        pool.execute(new Runnable() {
            @Override
            public void run() {
                for(int i = portRangeLow;  i <= portRangeHigh; i += 1) {
                    results.add(pool.submit(new Connector(hostname, i)));
                }
                submitCompleted.set(true);
            }
        });
    }

    public static void main(String[] args) {
        PortScanner ps = new PortScanner("guiney.net");
        ps.scanPorts();

        while(!ps.completed()) {
            ScanResult result = ps.getNextResult();
            System.out.println("Port " + result.getPort() + " open: " + result.isOpen());
        }

    }
}
