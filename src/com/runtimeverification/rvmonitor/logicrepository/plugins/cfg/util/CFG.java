package com.runtimeverification.rvmonitor.logicrepository.plugins.cfg.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;

public class CFG implements java.io.Serializable {
    private HashSet<Production> prods;
    private NonTerminal start;
    
    private HashSet<Terminal> ts = null;
    private HashSet<NonTerminal> nts = null;
    
    // WARNING the following cached queries should not be used after modification
    private HashMap<Symbol,HashSet<Terminal>> firstSet = null;
    private HashMap<Symbol,HashSet<Terminal>> followsSet = null;
    
    public CFG() {
        prods = new HashSet<Production>();
    }
    
    public void add(Production p) {
        prods.add(p);
    }
    
    public void add(Collection<Production> ps) {
        for (Production p : ps) {
            add(p);
        }
    }
    
    // Note these are only for match
    public String creationEvents() {
        HashMap<Terminal,HashSet<HashSet<Terminal>>> emap = enableSets();
        String ret = "";
        for (Terminal t : emap.keySet()) {
            if (emap.get(t).contains(new HashSet<Terminal>())) {
                ret+= t.toString();
            }
        }
        return ret;
    }
    
    // Note these are only for match
    public String enablesString() {
        return "// match Enables\n" + preenableSets().toString();
    }
    
    public String failenables() {
        HashMap<Terminal,HashSet<HashSet<Terminal>>> emap = new HashMap<Terminal,HashSet<HashSet<Terminal>>>();
        HashSet<HashSet<Terminal>> pow = Util.powerSet(terminals());
        for (Terminal t : terminals()) {
            emap.put(t,pow);
        }
        return "// fail Enables\n"+emap.toString();
    }
    
    // I wish java had typeclass deriving
    // This is meant for debuging, thus there is no effor to collapse alts
    public String toString() {
        String s = "Start: "+start.toString()+"\n";
        for (Production p : prods) {
            s += p.toString() + "\n";
        }
        return s;
    }
    
    // See Cooper and Torczon p 99
    // I am not quite sure what will happen if there are epsilons left in the grammar
    // before running this
    // Either way RVM doesn't need epsilons so we will use this latter
    private HashMap<Symbol,HashSet<Terminal>> firstS() {
        HashMap<Symbol,HashSet<Terminal>> ret = new HashMap<Symbol,HashSet<Terminal>>();
        HashSet<Symbol> temp = new HashSet<Symbol>();
        boolean changed = true;
        for (Terminal t : terminals()) {
            temp.add(t);
            ret.put(t,(HashSet<Terminal>)temp.clone());
            temp.clear();
        }
        for (NonTerminal nt : nonTerminals()) {
            ret.put(nt,new HashSet<Terminal>());
        }
        while (changed) {
            changed = false;
            for (Production p : prods) {
                if (!ret.get(p.getLhs()).containsAll(ret.get(p.getRhs().get(0)))) {
                    ret.get(p.getLhs()).addAll(ret.get(p.getRhs().get(0)));
                    changed = true;
                }
            }
        }
        return ret;
    }
    
    public HashSet<Terminal> first(Symbol t) { 
        if (firstSet == null) {
            firstSet = firstS(); 
        }
        return firstSet.get(t); 
    }
    
    private HashSet<Terminal> first(ArrayList<Symbol> l) { 
        if (l.isEmpty()) {
            return new HashSet<Terminal>();
        }
        return first(l.get(0));
    }
    
    // CT p99 only finds follows sets for nonterminals, but there should be no danger in finding them for terminals too
    private HashMap<Symbol,HashSet<Terminal>> followsS() {
        HashMap<Symbol,HashSet<Terminal>> ret = new HashMap<Symbol,HashSet<Terminal>>();
        boolean changed = true;
        for (Terminal t : terminals())
            ret.put(t,new HashSet<Terminal>());
        for (NonTerminal nt : nonTerminals())
            ret.put(nt,new HashSet<Terminal>());
        ret.get(start).add(new EOF());
        while (changed) {
            changed = false;
            for (Production p : prods) {
                ArrayList<Symbol> rhs = p.getRhs();
                changed = ret.get(rhs.get(rhs.size()-1)).addAll(ret.get(p.getLhs())) || changed;
                if (rhs.size() > 1) {
                    for (int i = rhs.size(); i > 0; i--) {
                        changed = ret.get(rhs.get(i-1)).addAll(first(rhs.get(i-1))) || changed;
                    }
                }
            }
        }
        return ret;
    }
    
    private HashSet<Terminal> follows(Symbol s) { 
        if (followsSet == null) {
            followsSet = followsS(); 
        }
        return followsSet.get(s); 
    }
    
    // What are the nonterminals in this grammar
    public HashSet<NonTerminal> nonTerminals() {
        if(nts != null) return nts;
        nts = new HashSet<NonTerminal>();
        for (Production p : prods) {
            nts.add(p.getLhs());
        }
        return nts;
    }
    
    public HashSet<Terminal> terminals() {
        if (ts != null) return ts;
        ts = new HashSet<Terminal>();
        for (Production p : prods) {
            for (Symbol s : p.getRhs()) {
                if (s instanceof Terminal) {
                    ts.add((Terminal)s);
                }
            }
        }
        return ts;
    }
    
    // The simplification conists the following steps:
    // 1) Remove nongenerating productions
    // 2) Remove nonreachable productions
    // 3) Inline/remove epsilons
    // 4) Remove A -> A productions
    // 5) Merge nonterminals that differ only in their names
    // 6) Add a new start symbol, this is mostly for LR table generation
    public void simplify() {
        removeNonGenerating();
        removeNonReachable();
        removeEpsilons();
        removeSelfLoops();
        removeDupes();
        newStart();
    }
    
    public void newStart() {
        NonTerminal nt = freshNT();
        ArrayList<Symbol> rhs = new ArrayList<Symbol>();
        rhs.add(start);
        prods.add(new Production(nt,rhs));
        start = nt;
    }
    
    public void removeStartCycles() {
        if (!prodsMentioning(start).isEmpty()) {
            NonTerminal nt = freshNT();
            ArrayList<Symbol> rhs = new ArrayList<Symbol>();
            rhs.add(start);
            prods.add(new Production(nt,rhs));
            start = nt;
        }
    }
    
    private int freshnamenum = 0;
    
    public NonTerminal freshNT() {
        HashSet<NonTerminal> nts = nonTerminals();
        while (nts.contains(new NonTerminal(Integer.toString(freshnamenum)))) {
            freshnamenum++;
        }
        return new NonTerminal(Integer.toString(freshnamenum));
    }
    
    public void removeDupes() {
        HashMap<HashSet<ArrayList<Symbol>>,NonTerminal> x = new HashMap<HashSet<ArrayList<Symbol>>,NonTerminal>();
        boolean changed = true;
        while (changed) {
            changed = false;
            x.clear();
            x.put(rhssOf(start),start);
            for (NonTerminal nt : nonTerminals()) {
                HashSet<ArrayList<Symbol>> rhss = rhssOf(nt);
                if (!x.containsKey(rhss)) {
                    x.put(rhss,nt);
                } else {
                    replaceRHSNTs(x.get(rhss),nt);
                }
            }
            changed = cropToNTs(x.values());
        }
    }
    
    private HashSet<ArrayList<Symbol>> rhssOf(NonTerminal nt) {
        HashSet<Production> ps = prodsOf(nt);
        HashSet<ArrayList<Symbol>> ret = new HashSet<ArrayList<Symbol>>();
        for (Production p : ps) {
            ret.add(p.getRhs());
        }
        return ret;
    }
    
    private void replaceRHSNTs(NonTerminal n, NonTerminal o) {
        for (Production p : prods) {
            p.replaceRHSNTs(n,o);
        }
    }
    
    public void removeEpsilons() {
        HashSet<Production> eps = epsilons();
        LinkedList<Production> work = new LinkedList<Production>();
        HashSet<Production> nps = new HashSet<Production>();
        Production p, ep, np;
        NonTerminal nt;
        while (hasEpsilon()) {
            ep = getEpsilon(); nt = ep.getLhs();
            work.addAll(prodsMentioning(nt));
            while (!work.isEmpty()) {
                p = work.remove();
                for (int i = 0; i < p.getRhs().size(); i++) {
                    if (p.getRhs().get(i).equals(nt) && p.getRhs().size() > 1) {
                        np = new Production(p);
                        np.getRhs().remove(i);
                        nps.add(np);
                        work.add(np);
                    }
                }
            }
            prods.remove(ep);
            prods.addAll(nps);
        }
    }
    
    // Return an epsilon production nondeterministically
    // Explodes if there are no epsilons
    private Production getEpsilon() {
        for(Production p : epsilons()) {
            return p;
        }
        return null;
    }
    
    private boolean hasEpsilon() {
        return !epsilons().isEmpty();
    }
    
    private HashSet<Production> prodsMentioning(NonTerminal nt) {
        HashSet<Production> ret = new HashSet<Production>();
        for (Production p : prods) {
            if (p.contains(nt)) {
                ret.add(p);
            }
        }
        return ret;
    }
    
    private HashSet<Production> prodsMentioning(Terminal t) {
        HashSet<Production> ret = new HashSet<Production>();
        for (Production p : prods) {
            if (p.contains(t)) {
                ret.add(p);
            }
        }
        return ret;
    }
    
    private HashSet<ArrayList<Symbol>> bodies() {
        HashSet<ArrayList<Symbol>> ret = new HashSet<ArrayList<Symbol>>();
        for (Production p : prods) {
            ret.add(p.getRhs());
        }
        return ret;
    }
    
    private HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>> genSets() {
        HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>> ret = new HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>>();
        HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>> old = new HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>>();
        
        // Initialize all prefixes of productions as well as symbols
        // Note that G({})={{}} not merely {}
        for (ArrayList<Symbol> rhs : bodies()) {
            for (ArrayList<Symbol> init : Util.inits(rhs)) {
                ret.put(init,new HashSet<HashSet<Terminal>>());
            }
        }
        for (Symbol t : terminals()) {
            ret.put(Util.singletonAL(t),new HashSet<HashSet<Terminal>>());
        }
        for (Symbol t : nonTerminals()) {
            ret.put(Util.singletonAL(t),new HashSet<HashSet<Terminal>>());
        }
        ret.put(new ArrayList<Symbol>(),Util.singletonHS(new HashSet<Terminal>()));
        
        // Terminals only generate themselves
        // Also these don't depend on any other value so don't
        // need to be in the fixed point section
        // G(t)={{t}}
        for (Terminal t : terminals()) {
            ret.put(Util.singletonAL((Symbol)t),Util.singletonHS(Util.singletonHS(t)));
        }
        
        // For Nonterminals we need to find a fixed point
        // G(V) = { G(...) : V -> ... }
        // G(D_1D_2) = { x U y : x \in G(D_1), y \in G(D_2) }
        while (!ret.equals(old)) {
            old = DeepCopy.copy(ret);
            for (Symbol nt : nonTerminals()) {
                for (Production p : prodsOf((NonTerminal)nt)) {
                    ret.get(Util.singletonAL(nt)).addAll(ret.get(p.getRhs()));
                }
            }
            for (ArrayList<Symbol> rhs : bodies()) {
                for (ArrayList<Symbol> init : Util.neinits(rhs)) {
                    ArrayList<Symbol> temp = (ArrayList<Symbol>)init.clone();
                    for (HashSet<Terminal> x : ret.get(Util.popl(temp))) {
                        for (HashSet<Terminal> y: ret.get(temp)) {
                            HashSet<Terminal> temp2 = DeepCopy.copy(x);
                            temp2.addAll(y);
                            ret.get(init).add(temp2);
                        }
                    }
                }
            }
        }
        return ret;
    }
    
    // Note this only finds the enable sets when ACCEPT is the goal category
    // I have no idea what to do for FAIL
    private HashMap<Terminal,HashSet<HashSet<Terminal>>> preenableSets() {
        HashMap<Symbol,HashSet<HashSet<Terminal>>> preenableSetsS = preenableSetsS();
        HashMap<Terminal,HashSet<HashSet<Terminal>>> ret = new HashMap<Terminal,HashSet<HashSet<Terminal>>>();
        for (Terminal t : terminals()) {
            ret.put(t,preenableSetsS.get(t));
        }
        return ret;
    }
    
    private HashMap<Symbol,HashSet<HashSet<Terminal>>> preenableSetsS() {
        HashMap<ArrayList<Symbol>,HashSet<HashSet<Terminal>>> genSets = genSets();
        HashMap<Symbol,HashSet<HashSet<Terminal>>> ret = new HashMap<Symbol,HashSet<HashSet<Terminal>>>();
        HashMap<Symbol,HashSet<HashSet<Terminal>>> old = null;
        HashMap<Symbol,HashSet<HashSet<Terminal>>> temp = null;
        
        // Initialize the maps to be empty
        // The start symbol has nothing infront of it
        for (Terminal t : terminals())
            ret.put(t,new HashSet<HashSet<Terminal>>());
        for (NonTerminal nt : nonTerminals())
            ret.put(nt,new HashSet<HashSet<Terminal>>());
        ret.put(start,Util.singletonHS(new HashSet<Terminal>()));
        
        while (!ret.equals(old)) {
            old = DeepCopy.copy(ret);
            temp = DeepCopy.copy(ret);
            for (Terminal t : terminals()) {
                for (Production p : prodsMentioning(t)) {
                    for (HashSet<Terminal> x : ret.get(p.getLhs())) {
                        for (ArrayList<Symbol> rhs : p.beforeSymS(t)) {
                            for (HashSet<Terminal> y : genSets.get(rhs)) {
                                temp.get(t).add(Util.termUnion(x,y));
                            }
                        }
                    }
                }
            }
            for (NonTerminal nt : nonTerminals()) {
                for (Production p : prodsMentioning(nt)) {
                    for (HashSet<Terminal> x : ret.get(p.getLhs())) {
                        for (ArrayList<Symbol> rhs : p.beforeSymS(nt)) {
                            for (HashSet<Terminal> y : genSets.get(rhs)) {
                                temp.get(nt).add(Util.termUnion(x,y));
                            }
                        }
                    }
                }
            }
            ret = temp;
        }
        return ret;
    }
    
    // Find the reverse of the grammar
    // We just need to reverse each production
    private void reverse() {
        for (Production p : prods) {
            for (int i = 0; i < p.getRhs().size()/2; i++) {
                Symbol temp = p.getRhs().get(i);
                p.getRhs().set(i,p.getRhs().get(p.getRhs().size()-(i+1)));
                p.getRhs().set(p.getRhs().size()-(i+1),temp);
            }
        }
    }
    
    // Like for enable sets this only works for match as the goal cat
    private HashMap<Terminal,HashSet<HashSet<Terminal>>> copreenableSets() {
        reverse();
        HashMap<Terminal,HashSet<HashSet<Terminal>>> ret = preenableSets();
        reverse();
        return ret;
    }
    
    private HashMap<Terminal,HashSet<HashSet<Terminal>>> enableSets() {
        HashMap<Terminal,HashSet<HashSet<Terminal>>> ret = new HashMap<Terminal,HashSet<HashSet<Terminal>>>();
        HashMap<Terminal,HashSet<HashSet<Terminal>>> preenableSets = preenableSets();
        for (Terminal t : terminals()) {
            HashSet<HashSet<Terminal>> temp = new HashSet<HashSet<Terminal>>();
            for (HashSet<Terminal> set : preenableSets.get(t))
                if (!set.contains(t)) temp.add(set);
                ret.put(t,temp);
        }
        return ret;
    }
    
    private HashMap<Terminal,HashSet<HashSet<Terminal>>> coenableSets() {
        HashMap<Terminal,HashSet<HashSet<Terminal>>> ret = copreenableSets();
        for (Terminal t : terminals())
            ret.get(t).remove(new HashSet<Terminal>());
        return ret;
    }
    
    // This looks to be quadratic and probably doesn't need to be
    public void removeNonGenerating() {
        HashSet<NonTerminal> gens = new HashSet<NonTerminal>();
        boolean changed = true;
        while (changed) {
            changed = false;
            HashSet<Symbol> temp = Util.union(gens,terminals());
            for (Production p : prods) {
                if (temp.containsAll(p.getRhs()) || p.isEpsilon()) {
                    changed = gens.add(p.getLhs()) || changed;
                }
            }
        }
        cropToNTs(gens);
    }
    
    // boolean return indicates whether this changed anything
    public boolean cropToNTs(Collection<NonTerminal> nts) {
        HashSet<Production> t = new HashSet<Production>();
        boolean ret;
        for (Production p : prods) {
            if (nts.contains(p.getLhs())) {
                t.add(p);
            }
        }
        ret = prods.size() > t.size();
        prods = t;
        return ret;
    }
    
    public void removeNonReachable() {
        LinkedList<NonTerminal> work = new LinkedList<NonTerminal>();
        HashSet<NonTerminal> r = new HashSet<NonTerminal>();
        HashSet<NonTerminal> nts;
        work.add(start); r.add(start);
        while (!work.isEmpty()) {
            for (Production p : prodsOf(work.remove())) {
                for (NonTerminal nt : p.nonTerminals()) {
                    if (!r.contains(nt)) {
                        r.add(nt); 
                        work.add(nt);
                    }
                }
            }
        }
        cropToNTs(r);
    }
    
    public HashSet<Production> prodsOf(NonTerminal nt) {
        HashSet<Production> r = new HashSet<Production>();
        for (Production p : prods) {
            if (p.getLhs().equals(nt)) {
                r.add(p);
            }
        }
        return r;
    }
    
    // With a name like this I wonder if we should just use a graph
    // library for the manipulations
    public void removeSelfLoops() { 
        prods.removeAll(selfLoops());
    }
    
    private HashSet<Production> epsilons() {
        HashSet<Production> ret = new HashSet<Production>();
        for (Production p : prods) {
            if (p.isEpsilon()) {
                ret.add(p);
            }
        }
        return ret;
    }
    
    private HashSet<Production> selfLoops() {
        HashSet<Production> ret = new HashSet<Production>();
        for (Production p : prods) {
            if (p.isSelfLoop()) {
                ret.add(p);
            }
        }
        return ret;
    }
    
    public java.util.Set<Production> getProds() {
        return java.util.Collections.unmodifiableSet(prods);
    }
    
    public NonTerminal getStart() {
        return start;
    }
    
    public void setStart(NonTerminal newStart) {
        this.start = newStart;
    }
}
