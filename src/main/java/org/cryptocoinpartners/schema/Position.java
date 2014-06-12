package org.cryptocoinpartners.schema;

import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.OneToOne;
import javax.persistence.Transient;


/**
 * A Position represents an withAmount of some Fungible within an Account.  The Position is owned by a Fund
 *
 * @author Tim Olson
 */
@Entity
public class Position extends EntityBase {


    // todo is Account one-to-one with Exchange?  Should we pass in the Account here instead?
    public Position(Fund fund, Exchange exchange, Fungible fungible, DiscreteAmount amount) {
        if( amount.getBasis() != fungible.getBasis() )
            throw new IllegalArgumentException("Basis for amount must match basis for Fungible");
        this.fund = fund;
        this.exchange = exchange;
        this.amountCount = amount.getCount();
        this.fungible = fungible;
    }


    @ManyToOne(optional = false) public Fund getFund() { return fund; }
    @ManyToOne(optional = false) public Exchange getExchange() { return exchange; }
    @Transient public DiscreteAmount getAmount() {
        if( amount == null )
            amount = new DiscreteAmount(amountCount,fungible.getBasis());
        return amount;
    }
    @OneToOne(optional = false) public Fungible getFungible() { return fungible; }


    // JPA
    protected Position() { }
    protected void setFund(Fund fund) { this.fund = fund; }
    protected void setExchange(Exchange exchange) { this.exchange = exchange; }
    protected void setFungible(Fungible fungible) { this.fungible = fungible; }
    protected long getAmountCount() { return amount.getCount(); }
    protected void setAmountCount(long amountCount) { this.amountCount = amountCount; }


    private Fund fund;
    private Exchange exchange;
    private DiscreteAmount amount;
    private long amountCount;
    private Fungible fungible;
}
