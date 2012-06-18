package com.webobjects.appserver._private;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.net.SocketException;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver._private.WOHTTPHeaderValue;
import com.webobjects.appserver._private.WOHTTPHeadersDictionary;
import com.webobjects.appserver._private.WOLowercaseCharArray;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSLog;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableData;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSMutableRange;
import com.webobjects.foundation.NSRange;
import com.webobjects.foundation._NSStringUtilities;

/**
 * Reimplementation to support streams bigger than 2.1GB.
 * 
 * @author jw
 */
public final class WOHttpIO {
	private static final int USE_KEEP_ALIVE_DEFAULT = 2;
	private int _keepAlive;
	private static final int _TheInputBufferSize = 2048;
	private static final int _TheOutputBufferSize = 2048;
	private static final int _HighWaterBufferSize;
	public static String URIResponseString = " Apple WebObjects\r\n";

	private final WOHTTPHeaderValue KeepAliveValue = new WOHTTPHeaderValue("keep-alive");
	private final WOHTTPHeaderValue CloseValue = new WOHTTPHeaderValue("close");
	private final WOLowercaseCharArray ConnectionKey = new WOLowercaseCharArray("connection");
	private final WOLowercaseCharArray ContentLengthKey = new WOLowercaseCharArray("content-length");
	private final WOLowercaseCharArray TransferEncodingKey = new WOLowercaseCharArray("transfer-encoding");
	
	private byte[] _buffer;
	private int _bufferLength;
	private int _bufferIndex;
	private int _lineStartIndex;
	StringBuffer _headersBuffer;
	public boolean _socketClosed = false;
	private final WOApplication _application;
	private static boolean _expectContentLengthHeader = true;

	private static int _contentTimeout = 5000;
	private final WOHTTPHeadersDictionary _headers;
	public static boolean _alwaysAppendContentLength = true;

	static {
		int value = Integer.getInteger("WOMaxIOBufferSize", 8196).intValue();
		if (value != 0) {
			_HighWaterBufferSize = value < 2048 ? 2048 : value;
		} else {
			_HighWaterBufferSize = 8196;
		}
	}

	public static void expectContentLengthHeader(boolean expectContentLengthHeader, int contentTimeout) {
		_expectContentLengthHeader = expectContentLengthHeader;
		_contentTimeout = contentTimeout;
	}

	public WOHttpIO() {
		_buffer = new byte[_TheInputBufferSize];
		_headersBuffer = new StringBuffer(_TheOutputBufferSize);
		_headers = new WOHTTPHeadersDictionary();
		_application = WOApplication.application();
	}

	private int _readBlob(InputStream inputStream, int length) throws IOException {
		byte[] leftOverBuffer = _buffer;
		int leftOverLength = _bufferLength - _bufferIndex;
		int leftOverStart = _bufferIndex;

		_ensureBufferIsLargeEnoughToRead(length - leftOverLength);
		if (_buffer != leftOverBuffer) {
			System.arraycopy(leftOverBuffer, leftOverStart, _buffer, 0, leftOverLength);
			_bufferLength = leftOverLength;
		}

		int read = leftOverLength;
		int newlyRead = 1;

		while (read < length && newlyRead > 0) {
			newlyRead = inputStream.read(_buffer, _bufferIndex + read, length - read);
			read += newlyRead;
		}

		return read > length ? length : read;
	}

	private int refillInputBuffer(InputStream inputStream) throws IOException {
		int moreLength = 0;
		boolean resetLineStartIndex = true;

		if (_bufferIndex >= 1) {
			if (_bufferLength < _buffer.length) {
				moreLength = inputStream.read(_buffer, _bufferLength, _buffer.length - _bufferLength);
				resetLineStartIndex = false;
			} else {
				byte[] leftOverBuffer = _buffer;

				int leftOverLength = _bufferLength - _lineStartIndex;
				int leftOverLineStartIndex = _lineStartIndex;

				_ensureBufferIsLargeEnoughToRead(_buffer.length);

				System.arraycopy(leftOverBuffer, leftOverLineStartIndex, _buffer, 0, leftOverLength);
				_bufferLength = leftOverLength;

				moreLength = inputStream.read(_buffer, leftOverLength, _buffer.length - leftOverLength);
				_bufferIndex = leftOverLength;
			}
		} else {
			_bufferLength = 0;
			_bufferIndex = 0;
			moreLength = inputStream.read(_buffer, 0, _buffer.length);
		}

		if (moreLength < 1) {
			return 0;
		}

		_bufferLength += moreLength;
		if (resetLineStartIndex) {
			_lineStartIndex = 0;
		}
		return _bufferLength;
	}

	public int readLine(InputStream inputStream) throws IOException {
		boolean foundNewline = false;
		boolean foundCR = false;
		boolean foundEnd = false;

		_lineStartIndex = _bufferIndex;
		do {
			for (; _bufferIndex < _bufferLength; _bufferIndex++) {
				if (foundNewline) {
					if (_buffer[_bufferIndex] == 9) {
						_buffer[_bufferIndex] = 32;
						foundNewline = foundCR = false;
					} else if (_buffer[_bufferIndex] == 32) {
						foundNewline = foundCR = false;
					} else {
						foundEnd = true;
					}
				} else if (_buffer[_bufferIndex] == 13) {
					_buffer[_bufferIndex] = 32;
					foundCR = true;
				} else if (_buffer[_bufferIndex] == 10) {
					_buffer[_bufferIndex] = 32;
					foundNewline = true;
					if (_bufferIndex - _lineStartIndex < 2) {
						foundEnd = true;
						_bufferIndex += 1;
					}
				}

				if (foundEnd) {
					break;
				}
			}
			if (_bufferIndex < _bufferLength || foundEnd) {
				continue;
			}
			if (refillInputBuffer(inputStream) == 0) {
				if (foundNewline) {
					break;
				}

				return 0;
			}
		} while (!foundEnd);

		int endSearchLocation = _bufferIndex;

		if (_bufferIndex > _bufferLength) {
			_bufferIndex = _bufferLength;
		}

		if (foundNewline) {
			endSearchLocation--;

			if (foundCR) {
				endSearchLocation--;
			}
		}

		return endSearchLocation - _lineStartIndex;
	}

	public void resetBuffer() {
		_bufferLength = 0;
		_bufferIndex = 0;
		_lineStartIndex = 0;
	}

	private void _ensureBufferIsLargeEnoughToRead(int length) {
		int newSize = _buffer.length;
		if (length + _bufferLength > newSize) {
			while (length + _bufferLength > newSize) {
				newSize <<= 1;
			}
			_buffer = new byte[newSize];

			resetBuffer();
		}
	}

	private void _shrinkBufferToHighWaterMark() {
		if (_buffer.length > _HighWaterBufferSize) {
			_buffer = new byte[_TheInputBufferSize];
			resetBuffer();
		}
	}

	public WORequest readRequestFromSocket(Socket connectionSocket) throws IOException {
		InputStream sis = connectionSocket.getInputStream();
		int p = 0;
		int q = 0;
		int offset = 0;

		WORequest aRequest = null;
		String aMethodString = null;
		String aURIString = null;
		String aHttpVersionString = null;

		resetBuffer();

		_headers.dispose();

		int lineLength = readLine(sis);
		if (lineLength == 0) {
			return null;
		}

		offset = _lineStartIndex;
		int lineLengthMinusOne = lineLength - 1;

		while (_buffer[p + offset] != 32 && p < lineLengthMinusOne) {
			p++;
		}
		if (p < lineLengthMinusOne) {
			q = lineLengthMinusOne;
			while (_buffer[q + offset] != 32 && q > p) {
				q--;
			}
			int _stringLength = lineLengthMinusOne - q;
			if (_stringLength > 0) {
				aHttpVersionString = _NSStringUtilities.stringForBytes(_buffer, q + offset + 1, _stringLength,
						WORequest.defaultHeaderEncoding());
			}

			_stringLength = q - p - 1;
			if (_stringLength > 0) {
				aURIString = _NSStringUtilities.stringForBytes(_buffer, p + offset + 1, _stringLength,
						WORequest.defaultHeaderEncoding());
			}

			_stringLength = p;
			if (_stringLength > 0) {
				aMethodString = _NSStringUtilities.stringForBytes(_buffer, offset, _stringLength,
						WORequest.defaultHeaderEncoding());
			}

		}

		_keepAlive = USE_KEEP_ALIVE_DEFAULT;

		InputStream pbsis = _readHeaders(sis, true, true, false);

		NSData contentData = null;
		NSArray headers = (NSArray) _headers.objectForKey(ContentLengthKey);
		if (headers != null && headers.count() == 1 && pbsis != null) {
			try {
				long contentLength = Long.parseLong(headers.lastObject().toString());

				if (contentLength > 0) {
					if (contentLength > Integer.MAX_VALUE) {
						contentData = new WOLargeInputStreamData(pbsis, contentLength);
					} else {
						contentData = new WOInputStreamData(pbsis, (int) contentLength);
					}
				}
			} catch (NumberFormatException e) {
				if (WOApplication._isDebuggingEnabled()) {
					NSLog.debug.appendln("<" + getClass().getName() + "> Unable to parse content-length header: '" + headers.lastObject() + "'.");
				}
			}
		} else {
			NSData fakeContentData = _content(sis, connectionSocket, false);

			if (fakeContentData != null) {
				contentData = new WOInputStreamData(fakeContentData);
			}
		}

		aRequest = _application.createRequest(aMethodString, aURIString, aHttpVersionString,
				_headers != null ? _headers.headerDictionary() : null, contentData, null);
		if (aRequest != null) {
			aRequest._setOriginatingAddress(connectionSocket.getInetAddress());
			aRequest._setOriginatingPort(connectionSocket.getPort());
			aRequest._setAcceptingAddress(connectionSocket.getLocalAddress());
			aRequest._setAcceptingPort(connectionSocket.getLocalPort());
		}

		_shrinkBufferToHighWaterMark();

		return aRequest;
	}

	private void appendMessageHeaders(WOMessage message) {
		NSDictionary<String, NSArray<String>> headers = message.headers();
		if (headers != null) {
			if (!(headers instanceof NSMutableDictionary)) {
				headers = headers.mutableClone();
			}
			((NSMutableDictionary) headers).removeObjectForKey(ContentLengthKey);

			NSArray<String> headerKeys = headers.allKeys();

			int kc = headerKeys.count();
			for (int i = 0; i < kc; i++) {
				Object aKey = headerKeys.objectAtIndex(i);
				NSArray<String> values = message.headersForKey(aKey);
				int vc = values.count();
				if ((aKey instanceof WOLowercaseCharArray)) {
					char[] aKeyCharArray = ((WOLowercaseCharArray) aKey).toCharArray();
					for (int j = 0; j < vc; j++) {
						_headersBuffer.append(aKeyCharArray);
						_headersBuffer.append(": ");
						_headersBuffer.append(values.objectAtIndex(j));
						_headersBuffer.append("\r\n");
					}
				} else {
					for (int j = 0; j < vc; j++) {
						_headersBuffer.append(aKey);
						_headersBuffer.append(": ");
						_headersBuffer.append(values.objectAtIndex(j));
						_headersBuffer.append("\r\n");
					}
				}
			}
		}
	}

	public boolean sendResponse(WOResponse aResponse, Socket connectionSocket, WORequest aRequest) throws IOException {
		String httpVersion = aResponse.httpVersion();

		_headersBuffer.setLength(0);
		_headersBuffer.append(httpVersion);
		_headersBuffer.append(' ');
		_headersBuffer.append(aResponse.status());
		_headersBuffer.append(URIResponseString);

		return sendMessage(aResponse, connectionSocket, httpVersion, aRequest);
	}

	public void sendRequest(WORequest aRequest, Socket connectionSocket) throws IOException {
		String httpVersion = aRequest.httpVersion();
		
		_headersBuffer.setLength(0);
		_headersBuffer.append(aRequest.method());
		_headersBuffer.append(' ');
		_headersBuffer.append(aRequest.uri());
		_headersBuffer.append(' ');
		_headersBuffer.append(httpVersion);
		_headersBuffer.append("\r\n");

		sendMessage(aRequest, connectionSocket, httpVersion, null);
	}

	protected boolean sendMessage(WOMessage aMessage, Socket connectionSocket, String httpVersion, WORequest aRequest)
			throws IOException {
		long length = 0L;
		NSData someContent = null;

		appendMessageHeaders(aMessage);
		boolean keepSocketAlive;
		if (isHTTP11(httpVersion)) {
			if (_keepAlive == 0) {
				_headersBuffer.append("connection: close\r\n");
				keepSocketAlive = false;
			} else {
				keepSocketAlive = true;
			}
		} else {
			if (_keepAlive == 1) {
				_headersBuffer.append("connection: keep-alive\r\n");
				keepSocketAlive = true;
			} else {
				keepSocketAlive = false;
			}

		}

		if (aRequest != null) {
			NSData contentData = aRequest.content();
			if (contentData != null && contentData instanceof WOInputStreamData) {
				WOInputStreamData isData = (WOInputStreamData) contentData;

				InputStream is = isData._stream();
				if (is != null && is instanceof WONoCopyPushbackInputStream) {
					WONoCopyPushbackInputStream sis = (WONoCopyPushbackInputStream) is;
					if (sis.wasPrematurelyTerminated()) {
						return false;
					}
					String contentLengthString = aRequest.headerForKey("content-length");
					long contentLength = contentLengthString != null ? Long.parseLong(contentLengthString) : 0L;
					if (contentLength > 0L) {
						int _originalReadTimeout = -1;
						try {
							_originalReadTimeout = setSocketTimeout(connectionSocket, _contentTimeout);

							sis.drain();

							if (NSLog.debugLoggingAllowedForLevelAndGroups(3, 4L)) {
								NSLog.out.appendln("<WOHttpIO>: Drained socket");
							}

							if (_originalReadTimeout != -1) {
								_originalReadTimeout = setSocketTimeout(connectionSocket, _originalReadTimeout);
							}
						} catch (SocketException socketException) {
							if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 4L)) {
								NSLog.err.appendln("<WOHttpIO>: Unable to set socket timeout:"
										+ socketException.getMessage());
								NSLog._conditionallyLogPrivateException(socketException);
							}
						} catch (IOException e) {
							if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 4L)) {
								NSLog.err.appendln("<WOHttpIO>: Finished reading before content length of "
										+ contentLength + " : " + e.getMessage());
								NSLog._conditionallyLogPrivateException(e);
							}
						}
					}
				}
			}
		}

		InputStream is = null;
		int bufferSize = 0;

		if (aMessage instanceof WOResponse) {
			WOResponse theResponse = (WOResponse) aMessage;
			is = theResponse.contentInputStream();
			if (is != null) {
				bufferSize = theResponse.contentInputStreamBufferSize();
				length = theResponse.contentInputStreamLength();
			}
		}

		if (is == null) {
			someContent = aMessage.content();
			length = someContent.length();
		}

		if (_alwaysAppendContentLength || length > 0L) {
			_headersBuffer.append("content-length: ");
			_headersBuffer.append(length);
		}

		_headersBuffer.append("\r\n\r\n");

		OutputStream outputStream = connectionSocket.getOutputStream();

		byte[] headerBytes = _NSStringUtilities.bytesForIsolatinString(new String(_headersBuffer));
		outputStream.write(headerBytes, 0, headerBytes.length);

		String method = aRequest != null ? aRequest.method() : "";
		boolean isHead = method.equals("HEAD");

		if (length > 0L && !isHead) {
			if (is == null) {
				NSMutableRange range = new NSMutableRange();
				byte[] contentBytesNoCopy = someContent != null ? someContent.bytesNoCopy(range) : new byte[0];
				outputStream.write(contentBytesNoCopy, range.location(), range.length());
			} else {
				try {
					byte[] buffer = new byte[bufferSize];
					while (length > 0L) {
						int read = is.read(buffer, 0, length > bufferSize ? bufferSize : (int) length);
						if (read == -1) {
							break;
						}
						length -= read;
						outputStream.write(buffer, 0, read);
					}
				} finally {
					try {
						is.close();
					} catch (Exception e) {
						NSLog.err.appendln("<WOHttpIO>: Failed to close content InputStream: " + e);
						if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 4L)) {
							NSLog.err.appendln(e);
						}
					}
				}
			}
		}
		outputStream.flush();

		return keepSocketAlive;
	}

	public WOResponse readResponseFromSocket(Socket connectionSocket) throws IOException {
		InputStream sis = connectionSocket.getInputStream();
		int p = 0;
		int q = 0;
		int offset = 0;

		WOResponse response = null;
		String statusCode = null;
		String httpVersion = null;

		resetBuffer();

		int lineLength = readLine(sis);
		if (lineLength == 0) {
			return null;
		}

		_NSStringUtilities.stringForBytes(_buffer, offset, lineLength, WORequest.defaultHeaderEncoding());
		offset = _lineStartIndex;
		int lineLengthMinusOne = lineLength - 1;

		while (_buffer[p + offset] != 32 && p < lineLengthMinusOne) {
			p++;
		}
		if (p < lineLengthMinusOne) {
			q = p + 1;
			while (_buffer[q + offset] != 32 && q < lineLengthMinusOne) {
				q++;
			}
			if (q < lineLengthMinusOne) {
				_NSStringUtilities.stringForBytes(_buffer, q + offset + 1, lineLengthMinusOne - q,
						WORequest.defaultHeaderEncoding());
			}
			statusCode = _NSStringUtilities.stringForBytes(_buffer, p + offset + 1, q - p - 1,
					WORequest.defaultHeaderEncoding());
			httpVersion = _NSStringUtilities.stringForBytes(_buffer, offset, p, WORequest.defaultHeaderEncoding());
		}

		if (_application != null) {
			response = _application.createResponseInContext(null);
		} else {
			response = new WOResponse();
		}
		response.setHTTPVersion(httpVersion);
		response.setStatus(Integer.parseInt(statusCode));

		_readHeaders(sis, false, false, false);
		response._setHeaders(_headers);

		boolean closeConnection = false;
		NSArray connectionStatus = (NSArray) _headers.valueForKey("Connection");
		if (connectionStatus != null) {
			int count = connectionStatus.count();
			for (int i = 0; i < count; i++) {
				String headerValue = (String) connectionStatus.objectAtIndex(i);
				if (headerValue.equalsIgnoreCase("close")) {
					closeConnection = true;
					break;
				}
			}
		}

		NSData contentData = _content(sis, connectionSocket, closeConnection);
		response.setContent(contentData);

		_shrinkBufferToHighWaterMark();

		if (closeConnection || (isHTTP11(httpVersion) && _keepAlive == 0)
				|| (!isHTTP11(httpVersion) && _keepAlive != 1)) {
			connectionSocket.close();
			_socketClosed = true;
		}

		return response;
	}

	private static final boolean isHTTP11(String httpVersion) {
		return httpVersion != null && "HTTP/1.1".equals(httpVersion);
	}

	public NSDictionary headers() {
		return _headers;
	}

	public InputStream _readHeaders(InputStream sis, boolean checkKeepAlive, boolean isRequest,
			boolean isMultipartHeaders) throws IOException {
		int offset = 0;
		while (true) {
			int lineLength = readLine(sis);
			if (lineLength == 0) {
				break;
			}
			offset = _lineStartIndex;

			int startValue = 0;
			int separator = 0;
			for (int i = 0; i < lineLength; i++) {
				if (_buffer[offset + i] == 58) {
					separator = i;
					i++;
					while (i < lineLength && _buffer[offset + i] == 32) {
						i++;
					}
					if (i < lineLength) {
						startValue = i;
						break;
					}
				}
			}
			if (startValue == 0) {
				continue;
			}

			int key_offset = offset;
			int key_length = separator;
			int value_offset = offset + startValue;
			int value_length = lineLength - startValue;

			WOHTTPHeaderValue headerValue = _headers.setBufferForKey(_buffer, value_offset, value_length, key_offset,
					key_length);
			WOLowercaseCharArray headerKey = _headers.lastInsertedKey();

			if (!checkKeepAlive || _keepAlive != USE_KEEP_ALIVE_DEFAULT || !ConnectionKey.equals(headerKey)) {
				continue;
			}
			if (headerValue.equalsIgnoreCase(KeepAliveValue)) {
				_keepAlive = 1;
				continue;
			}
			if (headerValue.equalsIgnoreCase(CloseValue)) {
				_keepAlive = 0;
			}
		}

		WONoCopyPushbackInputStream pbsis = null;
		int pushbackLength = _bufferLength - _bufferIndex;

		if (isRequest) {
			long contentLength = 0;
			NSArray headers = (NSArray) _headers.objectForKey(ContentLengthKey);
			if (headers != null && headers.count() == 1) {
				try {
					contentLength = Long.parseLong(headers.lastObject().toString());
				} catch (NumberFormatException e) {
					if (WOApplication._isDebuggingEnabled()) {
						NSLog.debug.appendln("<" + getClass().getName() + "> Unable to parse content-length header: '"
								+ headers.lastObject() + "'.");
					}

				}
				
				if (pushbackLength > contentLength) {
					contentLength = pushbackLength;
					_headers.setObjectForKey(new NSMutableArray("" + pushbackLength), ContentLengthKey);
				}

				long streamLength = contentLength - pushbackLength;
				pbsis = new WONoCopyPushbackInputStream(new BufferedInputStream(sis), streamLength);
			}

		} else if (isMultipartHeaders) {
			if (sis instanceof WONoCopyPushbackInputStream) {
				pbsis = (WONoCopyPushbackInputStream) sis;
			}

		}

		if (pbsis != null && pushbackLength > 0) {
			pbsis.unread(_buffer, _bufferIndex, pushbackLength);
		}

		return pbsis;
	}

	private NSData _forceReadContent(InputStream is, Socket connectionSocket) {
		int bytesRead = 0;
		NSMutableData _contentData = null;
		BufferedInputStream bis = new BufferedInputStream(is);
		byte[] buffer = new byte[_TheInputBufferSize];

		int _originalReadTimeout = setSocketTimeout(connectionSocket, _contentTimeout);

		if (_bufferLength > _bufferIndex) {
			_contentData = new NSMutableData(_bufferLength - _bufferIndex);
			_contentData.appendBytes(_buffer, new NSRange(_bufferIndex, _bufferLength - _bufferIndex));
		} else {
			_contentData = new NSMutableData();
		}
		while (true) {
			try {
				bytesRead = bis.read(buffer, 0, _TheInputBufferSize);
				if (bytesRead >= 0) {
					_contentData.appendBytes(buffer, new NSRange(0, bytesRead));
				} else {
					if (_originalReadTimeout == -1) {
						break;
					}
					_originalReadTimeout = setSocketTimeout(connectionSocket, _originalReadTimeout);
					break;
				}
				if (_originalReadTimeout != -1) {
					_originalReadTimeout = setSocketTimeout(connectionSocket, _originalReadTimeout);
				}
			} catch (IOException e) {
				if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 4L)) {
					NSLog.err.appendln("<WOHttpIO>: IOException occurred during read():" + e.getMessage());
					NSLog._conditionallyLogPrivateException(e);
				}
				return null; // CHECKME
			} finally {
				if (_originalReadTimeout != -1) {
					_originalReadTimeout = setSocketTimeout(connectionSocket, _originalReadTimeout);
				}
			}
		}
		return _contentData;
	}

	private NSData _content(InputStream is, Socket connectionSocket, boolean connectionClosed) throws IOException {
		byte[] content = null;

		long length = 0L;
		int offset = 0;
		NSData contentData = null;

		NSMutableArray contentLength = (NSMutableArray) _headers.objectForKey(ContentLengthKey);
		if (contentLength != null && contentLength.count() == 1) {
			try {
				length = Long.parseLong(contentLength.lastObject().toString());
			} catch (NumberFormatException e) {
				if (WOApplication._isDebuggingEnabled()) {
					NSLog.debug.appendln("<" + getClass().getName() + "> Unable to parse content-length header: '" + contentLength.lastObject() + "'.");
				}
			}
			if (length != 0L) {
				if (length > Integer.MAX_VALUE) {
					throw new IllegalStateException("Cannot read from input stream as length is bigger than " + Integer.MAX_VALUE + ".");
				}
				length = _readBlob(is, (int) length);

				offset = _bufferIndex;
				if (length > 0L) {
					content = _buffer;
				} else {
					offset = 0;
					length = 0L;
				}
			}

			try {
				if (content != null) {
					contentData = new NSData(content, new NSRange(offset, (int) length), true);
				}
			} catch (Exception e) {
				NSLog.err.appendln("<" + getClass().getName() + "> Error: Request creation failed!\n" + e);
				if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 8196L)) {
					NSLog.debug.appendln(e);
				}
			}
		} else {
			boolean readChunks = false;
			NSMutableArray encodingKeys = (NSMutableArray) _headers.objectForKey(TransferEncodingKey);
			if (encodingKeys != null && encodingKeys.count() == 1) {
				String encoding = (String) encodingKeys.lastObject();
				if ("chunked".equals(encoding)) {
					readChunks = true;
				}
			}
			if (readChunks) {
				contentData = _readChunks(is, connectionSocket);
			} else if (connectionClosed || !_expectContentLengthHeader) {
				contentData = _forceReadContent(is, connectionSocket);
			}
		}

		return contentData;
	}

	private NSData _readChunks(InputStream is, Socket socket) throws IOException {
		int _originalReadTimeout = setSocketTimeout(socket, _contentTimeout);
		try {
			int bytesInBuffer = _bufferLength - _bufferIndex;

			InputStream inputStream = null;
			if (bytesInBuffer > 0) {
				inputStream = new PushbackInputStream(is, bytesInBuffer);
				((PushbackInputStream) inputStream).unread(_buffer, _bufferIndex, bytesInBuffer);
			} else {
				inputStream = is;
			}
			resetBuffer();
			byte[] buffer = new byte[_TheInputBufferSize];
			NSMutableData result = new NSMutableData();
			while (true) {
				int contentBytesToRead = readChunkSizeLine(inputStream);
				int bytesRead;
				if (contentBytesToRead > 0) {
					contentBytesToRead += 2;
					if (contentBytesToRead > buffer.length) {
						buffer = new byte[contentBytesToRead];
					}
					bytesRead = inputStream.read(buffer, 0, contentBytesToRead);
					if (bytesRead > contentBytesToRead) {
						bytesRead = contentBytesToRead;
					}
					if (bytesRead > 0) {
						result.appendBytes(buffer, new NSRange(0, bytesRead - 2));
					}
				} else {
					return result;
				}
			}
		} finally {
			if (_originalReadTimeout != -1) {
				_originalReadTimeout = setSocketTimeout(socket, _originalReadTimeout);
			}
		}
	}

	private int readChunkSizeLine(InputStream is) throws IOException {
		int contentBytesToRead = 0;
		boolean skip = false;
		StringBuilder sb = new StringBuilder();
		while (true) {
			int b = is.read();
			sb.append((char) b);
			if (b == 59) {
				skip = true;
			} else if (b == 13) {
				is.read();
				break;
			}
			if (!skip) {
				int intVal = b >= 65 ? (b >= 97 ? b - 97 : b - 65) + 10 : b - 48;
				contentBytesToRead *= 16;
				contentBytesToRead += intVal;
			}
		}
		return contentBytesToRead;
	}

	protected int setSocketTimeout(Socket socket, int timeout) {
		int old = timeout;
		try {
			old = socket.getSoTimeout();
			if (timeout != -1) {
				socket.setSoTimeout(timeout);
			}
		} catch (SocketException e) {
			if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 8196L)) {
				NSLog.err.appendln("<WOHttpIO>: Unable to set socket timeout:" + e.getMessage());
			}
		}
		return old;
	}

	@Override
	public String toString() {
		return (new StringBuilder()).append("<").append(getClass().getName()).append(" keepAlive='").append(_keepAlive)
				.append("' buffer=").append(_buffer).append(" >").toString();
	}
}