package com.webobjects.appserver._private;

import java.io.InputStream;

import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSRange;

/**
 * Class that supports streams bigger than 2.1GB.
 * 
 * @author jw
 */
public class WOLargeInputStreamData extends WOInputStreamData {
	private static final long serialVersionUID = 1L;
	protected long _longStreamLength = 0L;
	
	public WOLargeInputStreamData(WOInputStreamData is) {
		super(is._stream(), is.streamLength());
		_longStreamLength = is.streamLength();
	}
	
	public WOLargeInputStreamData(InputStream inputStream, long length) {
		super(inputStream, 0);
		_longStreamLength = length;
	}
	
	@Override
	public int streamLength() {
		throw new UnsupportedOperationException();
	}
	
	public long longStreamLength() {
		return _longStreamLength;
	}
	
	@Override
	public String toString() {
		return (new StringBuilder()).append("<").append(getClass().getName()).append(" (stream ").append(_stream)
				.append(" of length ").append(_longStreamLength).append("), has ")
				.append(_hasAccessedStream ? "" : "NOT ").append("been accessed>").toString();
	}
	
	@Override
	public void setLength(int length) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void setData(NSData otherData) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void resetBytesInRange(NSRange range) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void appendByte(byte singleByte) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void appendBytes(byte[] bytes) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void appendBytes(byte[] bytes, NSRange range) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public void appendData(NSData otherData) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public Object clone() {
		throw new UnsupportedOperationException();
	}
}
