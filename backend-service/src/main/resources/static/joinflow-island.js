(function(global) {
  global.__JoinIsland = global.__JoinIsland || {
    mount: function(container, opts) {
      if (container) {
        container.setAttribute('data-joinflow-mounted', 'true');
      }
      return {
        update: function() {},
        destroy: function() {
          if (container) container.innerHTML = '';
        }
      };
    }
  };
})(typeof window !== 'undefined' ? window : this);
