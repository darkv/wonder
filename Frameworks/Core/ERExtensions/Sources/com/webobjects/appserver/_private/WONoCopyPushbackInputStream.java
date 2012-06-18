package com.webobjects.appserver._private;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.ListIterator;

/**
 * Reimplementation to support streams bigger than 2.1GB.
 * 
 * @author jw
 */
public class WONoCopyPushbackInputStream extends FilterInputStream {
	protected LinkedList<PushbackBuffer> buffers;
	protected byte[] oneByteArray;
	protected long readMax;
	protected long originalReadMax;
	protected boolean prematureTermination = false;

	public WONoCopyPushbackInputStream(InputStream is, long maxBytes) {
		super(is);
		buffers = new LinkedList();
		oneByteArray = new byte[1];
		readMax = maxBytes < 0L ? 0L : maxBytes;
		originalReadMax = readMax;
	}

	protected static class PushbackBuffer {
		public byte[] buf;
		public int pos;
		public int length;

		PushbackBuffer(byte[] paramArrayOfByte, int paramInt1, int paramInt2) {
			buf = paramArrayOfByte;
			pos = paramInt1;
			length = paramInt2;
		}
	}

	protected void ensureOpen() throws IOException {
		if (in == null) {
			throw new IOException("Stream closed");
		}
	}

	@Override
	public int read() throws IOException {
		int read = read(oneByteArray);
		if (read == -1) {
			return read;
		}
		return oneByteArray[0];
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		ensureOpen();
		int off = offset;
		int len = length;
		if (off < 0 || off > buffer.length || len < 0 || off + len > buffer.length || off + len < 0) {
			throw new IndexOutOfBoundsException();
		}

		if (len == 0) {
			return 0;
		}

		int avail = 0;
		PushbackBuffer pb = null;
		ListIterator li = buffers.listIterator(0);

		while (li.hasNext() && len > 0) {
			pb = (PushbackBuffer) li.next();
			int pbLen = pb.length;

			if (len < pbLen) {
				pbLen = len;
			}
			System.arraycopy(pb.buf, pb.pos, buffer, off, pbLen);
			avail += pbLen;

			pb.pos += pbLen;
			pb.length -= pbLen;

			off += pbLen;
			len -= pbLen;

			if (pb.length == 0) {
				li.remove();
			}
		}
		if (len > 0) {
			if (readMax <= 0L) {
				if (avail > 0) {
					return avail;
				}
				return -1;

			}

			if (len > readMax) {
				len = (int) readMax;
			}

			try {
				len = super.read(buffer, off, len);
			} catch (IOException e) {
				prematureTermination = true;
				throw e;
			}
			if (len == -1) {
				if (avail == 0) {
					if (readMax > 0L) {
						prematureTermination = true;

						throw new IOException("Connection reset by peer: Amount read didn't match content-length");
					}
					return -1;
				}
				return avail;
			}

			readMax -= len;
			return (avail + len);
		}
		return avail;
	}

	public void unread(byte[] b, int off, int len) throws IOException {
		ensureOpen();
		if (off < 0 || off > b.length || len < 0 || off + len > b.length || off + len < 0) {
			throw new IndexOutOfBoundsException();
		}
		if (len == 0) {
			return;
		}
		PushbackBuffer pb = new PushbackBuffer(b, off, len);
		buffers.addFirst(pb);
	}

	public void unread(byte[] b) throws IOException {
		unread(b, 0, b.length);
	}

	@Override
	public int available() throws IOException {
		ensureOpen();
		long avail = 0L;
		ListIterator li = buffers.listIterator(0);
		while (li.hasNext()) {
			avail += ((PushbackBuffer) li.next()).length;
		}
		avail += super.available();
		if (avail > Integer.MAX_VALUE) {
			avail = Integer.MAX_VALUE;
		}
		return (int) avail;
	}

	public long theoreticallyAvailable() {
		long avail = 0L;
		ListIterator li = buffers.listIterator(0);
		while (li.hasNext()) {
			avail += ((PushbackBuffer) li.next()).length;
		}
		avail += readMax;
		return avail;
	}

	@Override
	public long skip(long numberOfBytesToSkip) throws IOException {
		ensureOpen();
		long n = numberOfBytesToSkip;
		if (n <= 0L) {
			return 0L;
		}

		long pskip = 0L;
		PushbackBuffer pb = null;
		ListIterator li = buffers.listIterator(0);

		while (li.hasNext() && n > 0L) {
			pb = (PushbackBuffer) li.next();
			long pbLen = pb.length;

			if (n < pbLen) {
				pbLen = n;
			}
			n -= pbLen;
			pskip += pbLen;

			pb.pos += pbLen;
			pb.length -= pbLen;

			if (pb.length == 0) {
				li.remove();
			}
		}
		if (n > 0L) {
			if (n > readMax) {
				n = readMax;
			}
			long superSkip = 0L;
			try {
				superSkip = super.skip(n);
			} catch (IOException e) {
				prematureTermination = true;
				throw e;
			}
			if (superSkip == -1L) {
				if (pskip == 0L) {
					if (readMax > 0L) {
						prematureTermination = true;

						throw new IOException("Connection reset by peer: Amount read didn't match content-length");
					}
					return pskip;
				}
			}

			readMax = readMax - superSkip;
			pskip += superSkip;
		}
		return pskip;
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	@Override
	public synchronized void close() throws IOException {
		if (in != null) {
			in.close();
			in = null;
		}
		buffers = null;
	}

	public void drain() throws IOException {
		buffers = null;
		long toRead = readMax;
		int read = 0;
		byte[] drainBuffer = new byte[2048];

		while (toRead > 0L) {
			read = in.read(drainBuffer);
			if (read == -1) {
				return;
			}
			toRead -= read;
		}
	}

	public long readMax() {
		return readMax;
	}

	public long originalReadMax() {
		return originalReadMax;
	}

	public boolean wasPrematurelyTerminated() {
		return prematureTermination;
	}
}
