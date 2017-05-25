package org.cryptocoinpartners.schema;import java.util.HashMap;import java.util.Iterator;import java.util.List;import java.util.Map;import javax.annotation.Nullable;import javax.persistence.Basic;import javax.persistence.Cacheable;import javax.persistence.Entity;import javax.persistence.FetchType;import javax.persistence.ManyToOne;import javax.persistence.MapKeyJoinColumn;import javax.persistence.OneToMany;import javax.persistence.Transient;import org.cryptocoinpartners.enumeration.FeeMethod;import org.cryptocoinpartners.enumeration.PersistanceAction;import org.cryptocoinpartners.schema.dao.Dao;import org.cryptocoinpartners.schema.dao.ExchangeJpaDao;import org.cryptocoinpartners.util.ConfigUtil;import org.cryptocoinpartners.util.EM;import com.google.inject.Inject;import com.google.inject.assistedinject.Assisted;import com.google.inject.assistedinject.AssistedInject;/** * @author Tim Olson */@Entity@Cacheablepublic class Exchange extends EntityBase {    /**     *      */    //private static final long serialVersionUID = 4151431428629882383L;    private static Map<String, Exchange> exchangeMap = new HashMap<String, Exchange>();    /**     *      */    // @Inject    //protected static ExchangeJpaDao exchangeDao;    @Inject    protected transient static ExchangeJpaDao exchangeDao;    @Inject    protected transient static TransactionFactory transactionFactory;    @Inject    protected transient static ExchangeFactory exchangeFactory;    private volatile Map<Asset, Balance> balances;    public static Exchange forSymbolOrCreate(String symbol) {        Exchange found = forSymbol(symbol);        if (found == null) {            found = exchangeFactory.create(symbol);            //    new Exchange(symbol);            found.setRevision(found.getRevision() + 1);            exchangeMap.put(symbol, found);            try {                exchangeDao.persistEntities(found);            } catch (Throwable e) {                // TODO Auto-generated catch block                e.printStackTrace();            }        }        //  if (found.getBalances().isEmpty())        //     loadBalances(found);        return found;    }    public void loadBalances(Portfolio portfolio) {        final String configPrefix = "xchange";        //  Set<String> exchangeTags = XchangeUtil.getExchangeTags();        // for (String tag : exchangeTags) {        //     if (this.equals(XchangeUtil.getExchangeForTag(tag))) {        // three configs required:        // .class the full classname of the Xchange implementation        // .rate.queries rate limit the number of queries to this many (default: 1)        // .rate.period rate limit the number of queries during this period of time (default: 1 second)        // .listings identifies which Listings should be fetched from this exchange        String prefix = configPrefix + "." + getSymbol().toLowerCase() + '.';        if (getBalances() == null || getBalances().isEmpty()) {            List balances = ConfigUtil.combined().getList(prefix + "balances", null);            if (balances == null || balances.isEmpty())                return;            // final List listings = config.getList(prefix + "listings");            for (Iterator<List> il = balances.iterator(); il.hasNext();) {                Object balanceSymbol = il.next();                Balance balance = Balance.forSymbol(this, balanceSymbol.toString().toUpperCase());                if (balance.getAsset() == null || balance.getExchange() == null || balance.getAmount() == null)                    continue;                balance.persit();                //this.addBalance(balance);                log.debug("Exchange: Adding Balance " + balance + " to exchnage " + this);                addBalance(balance);                // DiscreteAmount price = new DiscreteAmount(0, balance.getAsset().getBasis());                // Transaction initialCredit = transactionFactory.create(portfolio, balance.getExchange(), balance.getAsset(), TransactionType.CREDIT,                //       balance.getAmount(), price);                //portfolio.getContext().setPublishTime(initialCredit);                //initialCredit.persit();                //portfolio.getContext().publish(initialCredit);                // market = context.getInjector().getInstance(Market.class).findOrCreate(coinTraderExchange, listing);                //markets.add(market);            }        }        // replace all markets with this        for (Tradeable tradeable : Portfolio.getMarkets()) {            if (!tradeable.isSynthetic()) {                Market market = (Market) tradeable;                if (market.getExchange().equals(this))                    market.setExchange(this);            }        }        //this.merge();        /* // } else {              log.info("Loading balances from persitance \"xchange." + this + ".*\"");              for (Iterator<Asset> il = getBalances().keySet().iterator(); il.hasNext();) {                  Asset balanceSymbol = il.next();                  //balance.persit();                  //this.addBalance(balance);                  log.debug("Exchange: publishing Balance " + getBalances().get(balanceSymbol) + " to exchnage " + this);                  DiscreteAmount price = new DiscreteAmount(0, balanceSymbol.getBasis());                  Transaction initialCredit = transactionFactory.create(portfolio, this, balanceSymbol, TransactionType.CREDIT, getBalances().get(balanceSymbol)                          .getAmount(), price);                  portfolio.getContext().setPublishTime(initialCredit);                  initialCredit.persit();                  portfolio.getContext().publish(initialCredit);                  // market = context.getInjector().getInstance(Market.class).findOrCreate(coinTraderExchange, listing);                  //markets.add(market);              }          }*/        //  }        //  }    }    public static Exchange forSymbolOrCreate(String symbol, int margin, double feeRate, FeeMethod feeMethod, boolean fillsProvided) {        Exchange found = forSymbol(symbol);        if (found == null) {            found = exchangeFactory.create(symbol, margin, feeRate, feeMethod, fillsProvided);            found.setRevision(found.getRevision() + 1);            exchangeMap.put(symbol, found);            try {                exchangeDao.persistEntities(found);            } catch (Throwable e) {                // TODO Auto-generated catch block                e.printStackTrace();            }        }        //  if (found.getBalances().isEmpty())        //    loadBalances(found);        return found;    }    public static Exchange forSymbolOrCreate(String symbol, int margin, double feeRate, FeeMethod feeMethod, double marginFeeRate, FeeMethod marginFeeMethod,            boolean fillsProvided) {        Exchange found = forSymbol(symbol);        if (found == null) {            found = exchangeFactory.create(symbol, margin, feeRate, feeMethod, marginFeeRate, marginFeeMethod, fillsProvided);            found.setRevision(found.getRevision() + 1);            exchangeMap.put(symbol, found);            try {                exchangeDao.persistEntities(found);            } catch (Throwable e) {                // TODO Auto-generated catch block                e.printStackTrace();            }            // exchangeDao.persist(found);        }        // if (found.getBalances().isEmpty())        //   loadBalances(found);        return found;    }    /** returns null if the symbol does not represent an existing exchange */    public static Exchange forSymbol(String symbol) {        if (exchangeMap.get(symbol) == null) {            Exchange exchange = EM.queryZeroOne(Exchange.class, "select e from Exchange e where symbol=?1", symbol);            if (exchange != null)                exchangeMap.put(symbol, exchange);        }        return exchangeMap.get(symbol);    }    public static List<String> allSymbols() {        return EM.queryList(String.class, "select symbol from Exchange");    }    @Basic(optional = false)    public String getSymbol() {        return symbol;    }    @Basic(optional = false)    public double getFeeRate() {        return feeRate;    }    protected void setFeeRate(double feeRate) {        this.feeRate = feeRate;    }    @Basic(optional = true)    public double getMarginFeeRate() {        return marginFeeRate;    }    protected void setMarginFeeRate(double marginFeeRate) {        this.marginFeeRate = marginFeeRate;    }    @ManyToOne(optional = false)    private FeeMethod feeMethod;    public FeeMethod getFeeMethod() {        return feeMethod;    }    protected void setFeeMethod(FeeMethod feeMethod) {        this.feeMethod = feeMethod;    }    @ManyToOne(optional = true)    private FeeMethod marginFeeMethod;    public FeeMethod getMarginFeeMethod() {        return marginFeeMethod;    }    protected void setMarginFeeMethod(FeeMethod marginFeeMethod) {        this.marginFeeMethod = marginFeeMethod;    }    @Basic(optional = true)    public boolean getFillsProvided() {        return fillsProvided;    }    protected void setFillsProvided(boolean fillsProvided) {        this.fillsProvided = fillsProvided;    }    @Basic(optional = false)    public int getMargin() {        return Math.max(margin, 1);    }    protected void setMargin(int margin) {        this.margin = margin;    }    @Override    public String toString() {        return symbol;    }    // JPA    protected Exchange() {    }    // @AssistedInject    // private Market(@Assisted Exchange exchange, @Assisted Listing listing, @Assisted("marketPriceBasis") double priceBasis,    //       @Assisted("marketVolumeBasis") double volumeBasis) {    protected void setSymbol(String symbol) {        this.symbol = symbol;    }    @AssistedInject    private Exchange(@Assisted String symbol, @Assisted int margin, @Assisted double feeRate, @Assisted FeeMethod feeMethod, @Assisted boolean fillsProvided) {        this.symbol = symbol;        this.margin = margin;        this.feeRate = feeRate;        this.feeMethod = feeMethod;        this.fillsProvided = fillsProvided;        this.balances = new HashMap<Asset, Balance>();    }    @AssistedInject    private Exchange(@Assisted String symbol, @Assisted int margin, @Assisted("feeRate") double feeRate, @Assisted("feeMethod") FeeMethod feeMethod,            @Assisted("marginFeeRate") double marginFeeRate, @Assisted("marginFeeMethod") FeeMethod marginFeeMethod, @Assisted boolean fillsProvided) {        this.symbol = symbol;        this.margin = margin;        this.feeRate = feeRate;        this.feeMethod = feeMethod;        this.marginFeeMethod = marginFeeMethod;        this.marginFeeRate = marginFeeRate;        this.fillsProvided = fillsProvided;        this.balances = new HashMap<Asset, Balance>();    }    @AssistedInject    public Exchange(@Assisted String symbol) {        //   return forSymbolOrCreate(symbol);        this.symbol = symbol;    }    private String symbol;    private int margin;    private double feeRate;    private double marginFeeRate;    private boolean fillsProvided;    public synchronized void addBalance(Balance balance) {        getBalances().put(balance.getAsset(), balance);        balance.setExchange(this);    }    public synchronized void removeBalance(Balance balance) {        getBalances().remove(balance.getAsset());        balance.setExchange(null);        //fill.setOrder(null);    }    public synchronized void removeBalances() {        for (Asset asset : getBalances().keySet())            getBalances().get(asset).setExchange(null);        getBalances().clear();        //  .remove(balance.getAsset());        //fill.setOrder(null);    }    @Override    public synchronized void persit() {        this.setPeristanceAction(PersistanceAction.NEW);        this.setRevision(this.getRevision() + 1);        try {            exchangeDao.persistEntities(this);        } catch (Throwable e) {            // TODO Auto-generated catch block            e.printStackTrace();        }        // TODO Auto-generated method stub    }    @Override    public EntityBase refresh() {        return exchangeDao.refresh(this);    }    @Override    public void detach() {        exchangeDao.detach(this);        // TODO Auto-generated method stub    }    @Override    public void merge() {        exchangeDao.merge(this);        // TODO Auto-generated method stub    }    @Nullable    @OneToMany(fetch = FetchType.EAGER, mappedBy = "exchange")    @MapKeyJoinColumn(name = "asset")    //@Column(name = "asset")    //  @OneToMany(fetch = FetchType.EAGER, mappedBy = "exchange")    //(mappedBy = "exchange")    //, fetch = FetchType.EAGER)    // ;;@OrderColumn(name = "time")    //, orphanRemoval = true, cascade = CascadeType.REMOVE)    // @OrderBy    //, cascade = { CascadeType.MERGE, CascadeType.REFRESH })    public Map<Asset, Balance> getBalances() {        return balances;    }    protected synchronized void setBalances(Map<Asset, Balance> balances) {        this.balances = balances;    }    @Override    @Transient    public Dao getDao() {        return exchangeDao;    }    @Override    @Transient    public void setDao(Dao dao) {        exchangeDao = (ExchangeJpaDao) dao;        // TODO Auto-generated method stub        //  return null;    }    @Override    public void delete() {        // TODO Auto-generated method stub    }    /* @Override     public int hashCode() {         final int prime = 31;         int result = 1;         result = prime * result + ((symbol == null) ? 0 : symbol.hashCode());         return result;     }     @Override     public boolean equals(Object obj) {         if (this == obj) {             return true;         }         if (obj == null) {             return false;         }         if (getClass() != obj.getClass()) {             return false;         }         Exchange other = (Exchange) obj;         if (symbol == null) {             if (other.symbol != null) {                 return false;             }         } else if (!symbol.equals(other.symbol)) {             return false;         }         return true;     }*/    @Override    public void prePersist() {        // TODO Auto-generated method stub    }    @Override    public void postPersist() {        // TODO Auto-generated method stub    }}