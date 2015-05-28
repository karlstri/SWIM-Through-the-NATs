package se.kth.swim;

import se.sics.kompics.network.Header;
import se.sics.kompics.network.Transport;
import se.sics.p2ptoolbox.util.network.NatedAddress;

public class Container<A extends NatedAddress> implements Header{

    private final Header<A> baseH;
    private final A entry;
    private final A next;
    
    public Container(Header<A> baseH, A EntryRelay,A NextRelay)
    {
        this.baseH = baseH;
        this.entry = EntryRelay;
        this.next=NextRelay;
    }
    
    public A getSource() {
        return entry;
    }

    public A getDestination() {
        return next;
    }

    public Transport getProtocol() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
    public A getActualSource() {
        return baseH.getSource();
    }
    public A getActualDestination() {
        return baseH.getDestination();
    }
    
    public Header<A> getActualHeader() {
        return baseH;
    }
}