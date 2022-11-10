//
//  Promise.swift
//  Plugin
//
//  Created by Jesse Onolememen on 10/11/2022.
//  Copyright © 2022 Capgo. All rights reserved.
//

import Foundation

class Promise<Value>: Future<Value> {
    init(value: Value? = nil) {
        super.init()
        
        // If the value was already known at the time the promise
        // was constructed, we can report it directly:
        result = value.map(Result.success)
    }
    
    func resolve(with value: Value) {
        result = .success(value)
    }
    
    func reject(with error: Error) {
        result = .failure(error)
    }
}
