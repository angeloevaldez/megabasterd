package com.tonikelope.megabasterd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.RandomAccessFile;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.CryptTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;

/**
 *
 * @author tonikelope
 */
public class ChunkUploader implements Runnable, SecureSingleThreadNotifiable {

    public static final int MAX_SLOT_ERROR = 3;
    private final int _id;
    private final Upload _upload;
    private volatile boolean _exit;
    private final Object _secure_notify_lock;
    private volatile boolean _error_wait;
    private boolean _notified;

    public ChunkUploader(int id, Upload upload) {
        _notified = false;
        _secure_notify_lock = new Object();
        _id = id;
        _upload = upload;
        _exit = false;
        _error_wait = false;
    }

    public void setExit(boolean exit) {
        _exit = exit;
    }

    public boolean isError_wait() {
        return _error_wait;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {

            _notified = true;

            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {

        synchronized (_secure_notify_lock) {
            while (!_notified) {

                try {
                    _secure_notify_lock.wait();
                } catch (InterruptedException ex) {
                    _exit = true;
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                }
            }

            _notified = false;
        }
    }

    public int getId() {
        return _id;
    }

    public boolean isExit() {
        return _exit;
    }

    public Upload getUpload() {
        return _upload;
    }

    public void setError_wait(boolean error_wait) {
        _error_wait = error_wait;
    }

    @Override
    public void run() {

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} ChunkUploader {1} hello! {2}", new Object[]{Thread.currentThread().getName(), getId(), getUpload().getFile_name()});

        try (final CloseableHttpClient httpclient = getApacheKissHttpClient(); RandomAccessFile f = new RandomAccessFile(_upload.getFile_name(), "r");) {

            String worker_url = _upload.getUl_url();

            int conta_error = 0;

            while (!_upload.getMain_panel().isExit() && !_exit && !_upload.isStopped() && conta_error < MAX_SLOT_ERROR) {

                int reads, http_status, tot_bytes_up;

                Chunk chunk = new Chunk(_upload.nextChunkId(), _upload.getFile_size(), worker_url, Upload.CHUNK_SIZE_MULTI);

                f.seek(chunk.getOffset());

                byte[] buffer = new byte[MainPanel.THROTTLE_SLICE_SIZE];

                do {

                    chunk.getOutputStream().write(buffer, 0, f.read(buffer, 0, Math.min((int) (chunk.getSize() - chunk.getOutputStream().size()), buffer.length)));

                } while (!_exit && !_upload.isStopped() && chunk.getOutputStream().size() < chunk.getSize());

                final HttpPost httppost = new HttpPost(new URI(chunk.getUrl()));

                final long postdata_length = chunk.getSize();

                tot_bytes_up = 0;

                boolean error = false;

                CloseableHttpResponse httpresponse = null;

                try {

                    if (!_exit && !_upload.isStopped()) {

                        final FutureTask<CloseableHttpResponse> futureTask;

                        try (CipherInputStream cis = new CipherInputStream(chunk.getInputStream(), genCrypter("AES", "AES/CTR/NoPadding", _upload.getByte_file_key(), forwardMEGALinkKeyIV(_upload.getByte_file_iv(), chunk.getOffset())))) {

                            final PipedOutputStream pipeout = new PipedOutputStream();

                            final PipedInputStream pipein = new PipedInputStream(pipeout);

                            futureTask = new FutureTask<>(new Callable() {
                                @Override
                                public CloseableHttpResponse call() throws IOException {

                                    httppost.setEntity(new InputStreamEntity(pipein, postdata_length));

                                    return httpclient.execute(httppost);
                                }
                            });

                            THREAD_POOL.execute(futureTask);

                            try (OutputStream out = new ThrottledOutputStream(pipeout, _upload.getMain_panel().getStream_supervisor())) {

                                Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Uploading chunk {1} from worker {2}...", new Object[]{Thread.currentThread().getName(), chunk.getId(), _id});

                                while (!_exit && !_upload.isStopped() && (reads = cis.read(buffer)) != -1) {
                                    out.write(buffer, 0, reads);

                                    _upload.getPartialProgress().add(reads);

                                    _upload.getProgress_meter().secureNotify();

                                    tot_bytes_up += reads;

                                    if (_upload.isPaused() && !_upload.isStopped()) {

                                        _upload.pause_worker();

                                        secureWait();

                                    } else if (!_upload.isPaused() && _upload.getMain_panel().getUpload_manager().isPaused_all()) {

                                        _upload.pause();

                                        _upload.pause_worker();

                                        secureWait();
                                    }
                                }

                            }
                        }

                        if (!_upload.isStopped()) {

                            try {

                                if (!_exit) {

                                    httpresponse = futureTask.get();

                                } else {

                                    futureTask.cancel(true);

                                    httpresponse = null;
                                }

                                if (httpresponse == null) {

                                    error = true;

                                } else if ((http_status = httpresponse.getStatusLine().getStatusCode()) != HttpStatus.SC_OK) {

                                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Failed : HTTP error code : {1}", new Object[]{Thread.currentThread().getName(), http_status});

                                    error = true;

                                } else {

                                    if (tot_bytes_up < chunk.getSize()) {

                                        error = true;

                                    } else {

                                        if (_upload.getCompletion_handle() == null) {

                                            InputStream is = httpresponse.getEntity().getContent();

                                            try (ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                                                while (_upload.getCompletion_handle() == null && (reads = is.read(buffer)) != -1) {

                                                    byte_res.write(buffer, 0, reads);
                                                }

                                                if (_upload.getCompletion_handle() == null) {

                                                    String response = new String(byte_res.toByteArray());

                                                    if (response.length() > 0) {

                                                        if (MegaAPI.checkMEGAError(response) != 0) {

                                                            Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} UPLOAD FAILED! (MEGA ERROR: {1})", new Object[]{Thread.currentThread().getName(), MegaAPI.checkMEGAError(response)});

                                                            error = true;

                                                        } else {

                                                            Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Completion handle -> {1}", new Object[]{Thread.currentThread().getName(), response});

                                                            _upload.setCompletion_handle(response);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (error && !_upload.isStopped()) {

                                    Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} Uploading chunk {1} from worker {2} FAILED!...", new Object[]{Thread.currentThread().getName(), chunk.getId(), _id});

                                    _upload.rejectChunkId(chunk.getId());

                                    if (tot_bytes_up > 0) {

                                        _upload.getPartialProgress().add(-1 * tot_bytes_up);

                                        _upload.getProgress_meter().secureNotify();
                                    }

                                    conta_error++;

                                    if (!_exit) {

                                        _error_wait = true;

                                        _upload.getView().updateSlotsStatus();

                                        Thread.sleep(getWaitTimeExpBackOff(conta_error) * 1000);

                                        _error_wait = false;

                                        _upload.getView().updateSlotsStatus();
                                    }

                                } else if (!error) {

                                    Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker {1} has uploaded chunk {2}", new Object[]{Thread.currentThread().getName(), _id, chunk.getId()});

                                    _upload.getMac_generator().getChunk_queue().put(chunk.getId(), chunk);

                                    _upload.getMac_generator().secureNotify();

                                    conta_error = 0;
                                }

                            } catch (ExecutionException | InterruptedException | CancellationException exception) {

                                error = true;

                                Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} Uploading chunk {1} from worker {2} FAILED!...", new Object[]{Thread.currentThread().getName(), chunk.getId(), _id});

                                _upload.rejectChunkId(chunk.getId());

                                if (tot_bytes_up > 0) {

                                    _upload.getPartialProgress().add(-1 * tot_bytes_up);

                                    _upload.getProgress_meter().secureNotify();
                                }

                                conta_error++;

                                if (!_exit) {

                                    _error_wait = true;

                                    _upload.getView().updateSlotsStatus();

                                    try {
                                        Thread.sleep(getWaitTimeExpBackOff(conta_error) * 1000);
                                    } catch (InterruptedException ex) {
                                        Logger.getLogger(ChunkUploader.class.getName()).log(Level.SEVERE, null, ex);
                                    }

                                    _error_wait = false;

                                    _upload.getView().updateSlotsStatus();
                                }

                            } finally {

                                if (httpresponse != null) {

                                    httpresponse.close();
                                }
                            }
                        }

                    } else if (_exit) {

                        _upload.rejectChunkId(chunk.getId());
                    }

                } catch (IOException ex) {

                    Logger.getLogger(getClass().getName()).log(Level.WARNING, "{0} Uploading chunk {1} from worker {2} FAILED!...", new Object[]{Thread.currentThread().getName(), chunk.getId(), _id});

                    _upload.rejectChunkId(chunk.getId());

                    if (tot_bytes_up > 0) {

                        _upload.getPartialProgress().add(-1 * tot_bytes_up);

                        _upload.getProgress_meter().secureNotify();
                    }

                    if (!(ex instanceof SocketTimeoutException)) {
                        conta_error++;
                    }

                    if (!_exit) {

                        _error_wait = true;

                        _upload.getView().updateSlotsStatus();

                        try {
                            Thread.sleep(getWaitTimeExpBackOff(conta_error) * 1000);
                        } catch (InterruptedException exception) {
                            Logger.getLogger(ChunkUploader.class.getName()).log(Level.SEVERE, null, exception);
                        }

                        _error_wait = false;

                        _upload.getView().updateSlotsStatus();
                    }

                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                } finally {

                    if (httpresponse != null) {
                        httpresponse.close();
                    }
                }

                if (_upload.getMain_panel().isExit()) {
                    secureWait();
                }
            }

            if (conta_error == MAX_SLOT_ERROR) {

                _upload.setStatus_error(true);

                _upload.stopUploader("UPLOAD FAILED: too many errors");

                Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, "UPLOAD FAILED: too many errors");
            }

        } catch (ChunkInvalidException e) {

        } catch (Exception ex) {

            _upload.stopUploader();

            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
        }

        _upload.stopThisSlot(this);

        _upload.getMac_generator().secureNotify();

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} ChunkUploader {1} bye bye...", new Object[]{Thread.currentThread().getName(), _id});
    }

}
