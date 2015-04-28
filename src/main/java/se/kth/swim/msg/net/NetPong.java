package se.kth.swim.msg.net;

import se.kth.swim.msg.Ping;
import se.kth.swim.msg.Pong;
import se.sics.kompics.network.Header;
import se.sics.p2ptoolbox.util.network.NatedAddress;

public class NetPong extends NetMsg<Pong>{

	public NetPong(Header<NatedAddress> header, Pong content) {
		super(header, content);
		// TODO Auto-generated constructor stub
	}

	@Override
	public NetMsg copyMessage(Header<NatedAddress> newHeader) {
		// TODO Auto-generated method stub
		return null;
	}

}
