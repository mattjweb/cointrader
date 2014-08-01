package org.cryptocoinpartners.schema;

import org.cryptocoinpartners.enumeration.TransactionType;
import org.joda.time.Duration;

import javax.persistence.Entity;


/**
 * A Transfer is a type of Transaction where Position(s) move between Accounts
 *
 * @author Tim Olson
 */
@Entity
public class Transfer extends Transaction {

    // todo

    public Transfer(Portfolio portfolio, Asset asset, TransactionType type, long priceCount, Amount volume) {
		super(portfolio, asset, type, priceCount, volume);
		// TODO Auto-generated constructor stub
	}

	private Duration estimatedDelay;

}
