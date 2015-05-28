package se.kth.swim.msg.net;

import se.kth.swim.msg.Ping;
import se.sics.kompics.network.Header;
import se.sics.p2ptoolbox.util.network.NatedAddress;

public class Container<C extends NetMsg<Object>> extends NetMsg<C>
{

	C data;
	public Container(Header<NatedAddress> header,C  content) 
	{
		super(header, content);
	}

	public Container(NatedAddress selfAddress, NatedAddress destAddress, C data)
	{
		super(selfAddress, destAddress, data);
		// TODO Auto-generated constructor stub
	}
	
	@Override
	public NetMsg copyMessage(Header<NatedAddress> newHeader)
	{
		return new Container(newHeader, data);
	}
	
}
